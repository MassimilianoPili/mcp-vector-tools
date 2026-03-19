package io.github.massimilianopili.mcp.vector.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SyncTracker {

    private static final Logger log = LoggerFactory.getLogger(SyncTracker.class);
    private final JdbcTemplate jdbc;

    public SyncTracker(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS embeddings_sync (
                    file_path TEXT PRIMARY KEY,
                    last_modified TIMESTAMPTZ NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    indexed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
        jdbc.execute("ALTER TABLE embeddings_sync ADD COLUMN IF NOT EXISTS chunk_version INTEGER DEFAULT 0");
    }

    public boolean needsReindex(Path file) {
        return isFileModified(file) || isVersionStale(file);
    }

    public boolean isFileModified(Path file) {
        try {
            Instant fileModified = Files.getLastModifiedTime(file).toInstant();
            List<Timestamp> rows = jdbc.query(
                    "SELECT last_modified FROM embeddings_sync WHERE file_path = ?",
                    (rs, i) -> rs.getTimestamp("last_modified"),
                    file.toString());

            if (rows.isEmpty()) return true;
            return fileModified.isAfter(rows.get(0).toInstant());
        } catch (Exception e) {
            log.warn("Errore check sync {}: {}", file, e.getMessage());
            return true;
        }
    }

    public boolean isVersionStale(Path file) {
        try {
            List<Integer> rows = jdbc.query(
                    "SELECT chunk_version FROM embeddings_sync WHERE file_path = ?",
                    (rs, i) -> rs.getInt("chunk_version"),
                    file.toString());

            if (rows.isEmpty()) return false; // file non tracciato, isFileModified lo cattura
            return rows.get(0) < TextSplitter.CHUNK_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    public void markIndexed(Path file, int chunkCount) {
        try {
            Instant fileModified = Files.getLastModifiedTime(file).toInstant();
            jdbc.update("""
                    INSERT INTO embeddings_sync (file_path, last_modified, chunk_count, indexed_at, chunk_version)
                    VALUES (?, ?, ?, NOW(), ?)
                    ON CONFLICT (file_path) DO UPDATE
                    SET last_modified = EXCLUDED.last_modified,
                        chunk_count = EXCLUDED.chunk_count,
                        indexed_at = NOW(),
                        chunk_version = EXCLUDED.chunk_version""",
                    file.toString(), Timestamp.from(fileModified), chunkCount, TextSplitter.CHUNK_VERSION);
        } catch (Exception e) {
            log.error("Errore aggiornamento sync {}: {}", file, e.getMessage());
        }
    }

    public void removeTracking(String filePath) {
        jdbc.update("DELETE FROM embeddings_sync WHERE file_path = ?", filePath);
    }

    public Set<String> getTrackedFiles(String extension) {
        String pattern = "%." + extension;
        return new HashSet<>(jdbc.query(
                "SELECT file_path FROM embeddings_sync WHERE file_path LIKE ?",
                (rs, i) -> rs.getString("file_path"),
                pattern));
    }

    public List<Map<String, Object>> getStats() {
        return jdbc.queryForList("""
                SELECT
                    CASE
                        WHEN file_path LIKE '%.jsonl' THEN 'conversation'
                        WHEN file_path LIKE '%.md' THEN 'docs'
                        ELSE 'other'
                    END AS type,
                    COUNT(*) AS file_count,
                    SUM(chunk_count) AS total_chunks,
                    MAX(indexed_at) AS last_indexed
                FROM embeddings_sync
                GROUP BY 1
                ORDER BY 1""");
    }

    public int countStaleFiles() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings_sync WHERE chunk_version < ?",
                Integer.class, TextSplitter.CHUNK_VERSION);
        return count != null ? count : 0;
    }

    public Map<String, Object> getVersionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("current_version", TextSplitter.CHUNK_VERSION);
        stats.put("stale_files", countStaleFiles());

        List<Map<String, Object>> byVersion = jdbc.queryForList("""
                SELECT chunk_version, COUNT(*) AS file_count, SUM(chunk_count) AS total_chunks
                FROM embeddings_sync
                GROUP BY chunk_version
                ORDER BY chunk_version""");
        stats.put("by_version", byVersion);
        return stats;
    }
}
