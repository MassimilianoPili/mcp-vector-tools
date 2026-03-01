package io.github.massimilianopili.mcp.vector.ingest;

import io.github.massimilianopili.mcp.vector.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final ConversationParser conversationParser;
    private final MarkdownParser markdownParser;
    private final SyncTracker syncTracker;
    private final VectorProperties properties;

    public ChunkingService(
            @Qualifier("vectorVectorStore") VectorStore vectorStore,
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
            ConversationParser conversationParser,
            MarkdownParser markdownParser,
            SyncTracker syncTracker,
            VectorProperties properties) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.conversationParser = conversationParser;
        this.markdownParser = markdownParser;
        this.syncTracker = syncTracker;
        this.properties = properties;
    }

    public Map<String, Object> reindexConversations() {
        String basePath = properties.getConversationsPath();
        if (basePath == null || basePath.isBlank()) {
            return Map.of("error", "mcp.vector.conversations-path non configurato");
        }

        int filesProcessed = 0;
        int filesSkipped = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<Path> jsonlFiles = findFiles(Path.of(basePath), "*.jsonl");
            Set<String> trackedFiles = syncTracker.getTrackedFiles("jsonl");

            for (Path file : jsonlFiles) {
                if (!syncTracker.needsReindex(file)) {
                    filesSkipped++;
                    trackedFiles.remove(file.toString());
                    continue;
                }

                try {
                    removeDocumentsForFile(file.toString());

                    List<Document> docs = conversationParser.parse(file);
                    if (!docs.isEmpty()) {
                        for (int i = 0; i < docs.size(); i += 50) {
                            int end = Math.min(i + 50, docs.size());
                            vectorStore.add(docs.subList(i, end));
                        }
                        syncTracker.markIndexed(file, docs.size());
                        totalChunks += docs.size();
                    }
                    filesProcessed++;
                    trackedFiles.remove(file.toString());
                } catch (Exception e) {
                    log.error("Errore indicizzazione {}: {}", file, e.getMessage());
                    errors.add(file.getFileName().toString() + ": " + e.getMessage());
                }
            }

            for (String deletedFile : trackedFiles) {
                removeDocumentsForFile(deletedFile);
                syncTracker.removeTracking(deletedFile);
            }

        } catch (Exception e) {
            return Map.of("error", "Errore scansione: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "conversation");
        result.put("files_processed", filesProcessed);
        result.put("files_skipped", filesSkipped);
        result.put("total_chunks", totalChunks);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    public Map<String, Object> reindexDocs() {
        String basePath = properties.getDocsPath();
        if (basePath == null || basePath.isBlank()) {
            return Map.of("error", "mcp.vector.docs-path non configurato");
        }

        int filesProcessed = 0;
        int filesSkipped = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<Path> mdFiles = findMarkdownFiles(Path.of(basePath));
            Set<String> trackedFiles = syncTracker.getTrackedFiles("md");

            for (Path file : mdFiles) {
                if (!syncTracker.needsReindex(file)) {
                    filesSkipped++;
                    trackedFiles.remove(file.toString());
                    continue;
                }

                try {
                    removeDocumentsForFile(file.toString());

                    List<Document> docs = markdownParser.parse(file);
                    if (!docs.isEmpty()) {
                        for (int i = 0; i < docs.size(); i += 50) {
                            int end = Math.min(i + 50, docs.size());
                            vectorStore.add(docs.subList(i, end));
                        }
                        syncTracker.markIndexed(file, docs.size());
                        totalChunks += docs.size();
                    }
                    filesProcessed++;
                    trackedFiles.remove(file.toString());
                } catch (Exception e) {
                    log.error("Errore indicizzazione {}: {}", file, e.getMessage());
                    errors.add(file.getFileName().toString() + ": " + e.getMessage());
                }
            }

            for (String deletedFile : trackedFiles) {
                removeDocumentsForFile(deletedFile);
                syncTracker.removeTracking(deletedFile);
            }

        } catch (Exception e) {
            return Map.of("error", "Errore scansione: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "docs");
        result.put("files_processed", filesProcessed);
        result.put("files_skipped", filesSkipped);
        result.put("total_chunks", totalChunks);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    private List<Path> findFiles(Path basePath, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(basePath)) return result;

        Files.walk(basePath)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(
                        glob.replace("*", "")))
                .forEach(result::add);
        return result;
    }

    private List<Path> findMarkdownFiles(Path basePath) throws IOException {
        List<Path> result = new ArrayList<>();

        addIfExists(result, basePath.resolve("CLAUDE.md"));
        addIfExists(result, basePath.resolve("README.md"));

        Path docsDir = basePath.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(docsDir, "*.md")) {
                stream.forEach(result::add);
            }
        }

        Path variDir = basePath.resolve("Vari");
        if (Files.isDirectory(variDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(variDir)) {
                for (Path subDir : stream) {
                    if (Files.isDirectory(subDir)) {
                        addIfExists(result, subDir.resolve("CLAUDE.md"));
                    }
                }
            }
        }

        Path memoryFile = Path.of(System.getProperty("user.home"),
                ".claude/projects/-data-massimiliano/memory/MEMORY.md");
        addIfExists(result, memoryFile);

        return result;
    }

    private void addIfExists(List<Path> list, Path file) {
        if (Files.isRegularFile(file)) list.add(file);
    }

    private void removeDocumentsForFile(String filePath) {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM vector_store WHERE metadata->>'source_file' = ?", filePath);
            if (deleted > 0) {
                log.debug("Rimossi {} vecchi embedding per {}", deleted, filePath);
            }
        } catch (Exception e) {
            log.warn("Errore rimozione embedding per {}: {}", filePath, e.getMessage());
        }
    }
}
