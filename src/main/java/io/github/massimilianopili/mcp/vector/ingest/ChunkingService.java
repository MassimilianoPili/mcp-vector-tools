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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final ConversationParser conversationParser;
    private final MarkdownParser markdownParser;
    private final CodeParser codeParser;  // nullable — tree-sitter JNI may not load
    private final SyncTracker syncTracker;
    private final VectorProperties properties;

    private static final List<String> CODE_EXTENSIONS = List.of(
            ".java", ".go", ".py", ".ts", ".tsx", ".js", ".jsx", ".c", ".h", ".rs");

    public ChunkingService(
            @Qualifier("vectorVectorStore") VectorStore vectorStore,
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
            ConversationParser conversationParser,
            MarkdownParser markdownParser,
            @org.springframework.lang.Nullable CodeParser codeParser,
            SyncTracker syncTracker,
            VectorProperties properties) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.conversationParser = conversationParser;
        this.markdownParser = markdownParser;
        this.codeParser = codeParser;
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
        int filesMigrated = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<Path> jsonlFiles = findFiles(Path.of(basePath), "*.jsonl");
            Set<String> trackedFiles = syncTracker.getTrackedFiles("jsonl");

            for (Path file : jsonlFiles) {
                boolean fileChanged = syncTracker.isFileModified(file);
                boolean versionStale = syncTracker.isVersionStale(file);

                if (!fileChanged && !versionStale) {
                    filesSkipped++;
                    trackedFiles.remove(file.toString());
                    continue;
                }

                // Version-only migration: limita a MIGRATION_BATCH_LIMIT per run
                if (!fileChanged && versionStale) {
                    if (filesMigrated >= MIGRATION_BATCH_LIMIT) {
                        filesSkipped++;
                        trackedFiles.remove(file.toString());
                        continue;
                    }
                    filesMigrated++;
                }

                try {
                    removeDocumentsForFile(file.toString());

                    List<Document> docs = conversationParser.parse(file);
                    if (!docs.isEmpty()) {
                        addWithRetry(docs);
                        syncTracker.markIndexed(file, docs.size());
                        totalChunks += docs.size();
                    }
                    filesProcessed++;
                    trackedFiles.remove(file.toString());
                } catch (Exception e) {
                    log.error("Errore indicizzazione {}: {}", file, e.getMessage());
                    errors.add(file.getFileName().toString() + ": " + e.getMessage());
                    syncTracker.markIndexed(file, 0);
                    trackedFiles.remove(file.toString());
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
        result.put("files_migrated", filesMigrated);
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
        int filesMigrated = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<Path> mdFiles = findMarkdownFiles(Path.of(basePath));
            Set<String> trackedFiles = syncTracker.getTrackedFiles("md");

            for (Path file : mdFiles) {
                boolean fileChanged = syncTracker.isFileModified(file);
                boolean versionStale = syncTracker.isVersionStale(file);

                if (!fileChanged && !versionStale) {
                    filesSkipped++;
                    trackedFiles.remove(file.toString());
                    continue;
                }

                // Version-only migration: limita a MIGRATION_BATCH_LIMIT per run
                if (!fileChanged && versionStale) {
                    if (filesMigrated >= MIGRATION_BATCH_LIMIT) {
                        filesSkipped++;
                        trackedFiles.remove(file.toString());
                        continue;
                    }
                    filesMigrated++;
                }

                try {
                    removeDocumentsForFile(file.toString());

                    List<Document> docs = markdownParser.parse(file);
                    if (!docs.isEmpty()) {
                        addWithRetry(docs);
                        syncTracker.markIndexed(file, docs.size());
                        totalChunks += docs.size();
                    }
                    filesProcessed++;
                    trackedFiles.remove(file.toString());
                } catch (Exception e) {
                    log.error("Errore indicizzazione {}: {}", file, e.getMessage());
                    errors.add(file.getFileName().toString() + ": " + e.getMessage());
                    syncTracker.markIndexed(file, 0);
                    trackedFiles.remove(file.toString());
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
        result.put("files_migrated", filesMigrated);
        result.put("files_skipped", filesSkipped);
        result.put("total_chunks", totalChunks);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    public Map<String, Object> reindexCode() {
        if (codeParser == null) {
            return Map.of("error", "CodeParser not available (tree-sitter JNI not loaded)");
        }
        String codePathsConfig = properties.getCodePaths();
        if (codePathsConfig == null || codePathsConfig.isBlank()) {
            return Map.of("error", "mcp.vector.code-paths non configurato");
        }

        int filesProcessed = 0;
        int filesSkipped = 0;
        int filesMigrated = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        try {
            String[] roots = codePathsConfig.split(",");
            List<Path> codeFiles = new ArrayList<>();
            for (String root : roots) {
                codeFiles.addAll(findCodeFiles(Path.of(root.trim())));
            }

            Set<String> trackedFiles = syncTracker.getTrackedFiles("code");

            for (Path file : codeFiles) {
                boolean fileChanged = syncTracker.isFileModified(file);
                boolean versionStale = syncTracker.isVersionStale(file);

                if (!fileChanged && !versionStale) {
                    filesSkipped++;
                    trackedFiles.remove(file.toString());
                    continue;
                }

                if (!fileChanged && versionStale) {
                    if (filesMigrated >= MIGRATION_BATCH_LIMIT) {
                        filesSkipped++;
                        trackedFiles.remove(file.toString());
                        continue;
                    }
                    filesMigrated++;
                }

                try {
                    removeDocumentsForFile(file.toString());

                    List<Document> docs = codeParser.parse(file);
                    if (!docs.isEmpty()) {
                        addWithRetry(docs);
                        syncTracker.markIndexed(file, docs.size());
                        totalChunks += docs.size();
                    }
                    filesProcessed++;
                    trackedFiles.remove(file.toString());
                } catch (Exception e) {
                    log.error("Errore indicizzazione codice {}: {}", file, e.getMessage());
                    errors.add(file.getFileName().toString() + ": " + e.getMessage());
                    syncTracker.markIndexed(file, 0);
                    trackedFiles.remove(file.toString());
                }
            }

            for (String deletedFile : trackedFiles) {
                removeDocumentsForFile(deletedFile);
                syncTracker.removeTracking(deletedFile);
            }

        } catch (Exception e) {
            return Map.of("error", "Errore scansione codice: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "code");
        result.put("files_processed", filesProcessed);
        result.put("files_migrated", filesMigrated);
        result.put("files_skipped", filesSkipped);
        result.put("total_chunks", totalChunks);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    private List<Path> findCodeFiles(Path basePath) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(basePath)) return result;

        Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return CODE_EXTENSIONS.stream().anyMatch(name::endsWith);
                })
                .filter(p -> {
                    String path = p.toString();
                    return EXCLUDED_DIRS.stream().noneMatch(path::contains);
                })
                .filter(p -> !p.toString().contains("/code-server/"))
                .filter(p -> !p.toString().contains("/ollama/"))
                .forEach(result::add);

        log.info("Trovati {} file codice da indicizzare sotto {}", result.size(), basePath);
        return result;
    }

    /**
     * Avvia reindex asincrono su un thread platform (non virtual) per evitare InterruptedException
     * dal timeout del tool MCP. Ritorna immediatamente con stato "started".
     */
    public Map<String, Object> reindexAsync(String type) {
        if (indexingInProgress.get()) {
            return Map.of("status", "already_running", "message", "Indicizzazione gia' in corso");
        }

        indexingInProgress.set(true);
        Thread.ofPlatform().name("embeddings-reindex").start(() -> {
            try {
                Map<String, Object> result = new HashMap<>();
                if ("conversation".equals(type) || "all".equals(type)) {
                    result.put("conversations", reindexConversations());
                }
                if ("docs".equals(type) || "all".equals(type)) {
                    result.put("docs", reindexDocs());
                }
                if ("code".equals(type) || "all".equals(type)) {
                    result.put("code", reindexCode());
                }
                lastResult.set(result);
                log.info("Reindex completato: {}", result);
            } catch (Exception e) {
                lastResult.set(Map.of("error", e.getMessage()));
                log.error("Reindex fallito: {}", e.getMessage(), e);
            } finally {
                indexingInProgress.set(false);
            }
        });

        return Map.of("status", "started", "type", type,
                "message", "Indicizzazione avviata in background. Usa embeddings_stats per monitorare.");
    }

    public Map<String, Object> getReindexStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("indexing_in_progress", indexingInProgress.get());
        status.put("last_result", lastResult.get());
        return status;
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

    private static final List<String> EXCLUDED_DIRS = List.of(
            "/node_modules/", "/.git/", "/target/", "/claude-shared/plugins/cache/",
            "/.m2/", "/.cache/", "/.local/", "/vendor/", "/build/",
            "/code-server/local/", "/code-server/config/", "/.config/");

    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> lastResult = new AtomicReference<>(Map.of());

    private List<Path> findMarkdownFiles(Path basePath) throws IOException {
        List<Path> result = new ArrayList<>();

        // Scan ricorsivo di tutti i .md sotto basePath
        if (Files.isDirectory(basePath)) {
            Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> {
                        String path = p.toString();
                        return EXCLUDED_DIRS.stream().noneMatch(path::contains);
                    })
                    .forEach(result::add);
        }

        // MEMORY.md (fuori da basePath)
        Path memoryFile = Path.of(System.getProperty("user.home"),
                ".claude/projects/-data-massimiliano/memory/MEMORY.md");
        addIfExists(result, memoryFile);

        log.info("Trovati {} file .md da indicizzare sotto {}", result.size(), basePath);
        return result;
    }

    private void addIfExists(List<Path> list, Path file) {
        if (Files.isRegularFile(file)) list.add(file);
    }

    private static final int MIGRATION_BATCH_LIMIT = 250;
    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 20;

    private void addWithRetry(List<Document> docs) {
        for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, docs.size());
            List<Document> batch = docs.subList(i, end);
            int attempt = 0;
            while (true) {
                try {
                    vectorStore.add(batch);
                    break;
                } catch (Exception e) {
                    String msg = e.getMessage();
                    // Errori HTTP 4xx: permanenti, skip immediato (no retry)
                    if (msg != null && (msg.contains("400 -") || msg.contains("413 -")
                            || msg.contains("422 -") || msg.contains("context length"))) {
                        throw e;
                    }
                    // Errori transitori (5xx, timeout, connection reset): retry con backoff
                    attempt++;
                    if (attempt >= MAX_RETRIES) throw e;
                    long delay = attempt * 5000L;
                    log.warn("Retry {}/{} embedding batch ({}): {}",
                            attempt, MAX_RETRIES, batch.size(), msg);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrotto durante retry", ie);
                    }
                }
            }
        }
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
