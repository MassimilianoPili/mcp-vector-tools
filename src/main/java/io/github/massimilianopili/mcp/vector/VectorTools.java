package io.github.massimilianopili.mcp.vector;

import io.github.massimilianopili.mcp.vector.ingest.ChunkingService;
import io.github.massimilianopili.mcp.vector.ingest.SyncTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
public class VectorTools {

    private static final Logger log = LoggerFactory.getLogger(VectorTools.class);

    private final VectorStore vectorStore;
    private final ChunkingService chunkingService;
    private final SyncTracker syncTracker;

    public VectorTools(
            @Qualifier("vectorVectorStore") VectorStore vectorStore,
            ChunkingService chunkingService,
            SyncTracker syncTracker) {
        this.vectorStore = vectorStore;
        this.chunkingService = chunkingService;
        this.syncTracker = syncTracker;
    }

    @Tool(name = "embeddings_search",
          description = "Ricerca semantica nel vector store. Trova documenti simili al testo fornito. "
                      + "Supporta filtri per tipo (conversation, docs). Ritorna i chunk piu' rilevanti con metadata.")
    public List<Map<String, Object>> search(
            @ToolParam(description = "Testo da cercare semanticamente") String query,
            @ToolParam(description = "Numero massimo di risultati (default 5, max 20)", required = false) Integer topK,
            @ToolParam(description = "Filtro per tipo: conversation, docs (se omesso cerca in tutti)", required = false) String type,
            @ToolParam(description = "Soglia di similarita' minima 0.0-1.0 (default 0.7)", required = false) Double threshold) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
            double th = (threshold != null && threshold > 0) ? threshold : 0.7;

            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(k)
                    .similarityThreshold(th);

            if (type != null && !type.isBlank()) {
                builder.filterExpression("type == '" + type + "'");
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            return formatResults(results);
        } catch (Exception e) {
            log.error("Errore ricerca: {}", e.getMessage());
            return List.of(Map.of("error", "Errore ricerca: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_search_conversations",
          description = "Ricerca semantica nelle conversazioni Claude Code. "
                      + "Ritorna il testo del chunk, session_id, turno, e file sorgente.")
    public List<Map<String, Object>> searchConversations(
            @ToolParam(description = "Testo da cercare nelle conversazioni") String query,
            @ToolParam(description = "Numero massimo di risultati (default 5)", required = false) Integer topK) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(k)
                    .similarityThreshold(0.65)
                    .filterExpression("type == 'conversation'")
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            return formatResults(results);
        } catch (Exception e) {
            log.error("Errore ricerca conversazioni: {}", e.getMessage());
            return List.of(Map.of("error", "Errore: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_search_docs",
          description = "Ricerca semantica nella documentazione infrastruttura (CLAUDE.md, README, docs/). "
                      + "Ritorna sezioni rilevanti con file_path e heading.")
    public List<Map<String, Object>> searchDocs(
            @ToolParam(description = "Testo da cercare nella documentazione") String query,
            @ToolParam(description = "Numero massimo di risultati (default 5)", required = false) Integer topK) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(k)
                    .similarityThreshold(0.65)
                    .filterExpression("type == 'docs'")
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            return formatResults(results);
        } catch (Exception e) {
            log.error("Errore ricerca docs: {}", e.getMessage());
            return List.of(Map.of("error", "Errore: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_stats",
          description = "Mostra statistiche sugli embedding indicizzati: "
                      + "conteggio per tipo, numero di file e chunk, ultimo aggiornamento.")
    public Map<String, Object> stats() {
        try {
            List<Map<String, Object>> typeStats = syncTracker.getStats();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("types", typeStats);

            int totalFiles = 0;
            long totalChunks = 0;
            for (Map<String, Object> stat : typeStats) {
                totalFiles += ((Number) stat.get("file_count")).intValue();
                totalChunks += ((Number) stat.get("total_chunks")).longValue();
            }
            result.put("total_files", totalFiles);
            result.put("total_chunks", totalChunks);
            return result;
        } catch (Exception e) {
            return Map.of("error", "Errore statistiche: " + e.getMessage());
        }
    }

    @Tool(name = "embeddings_reindex",
          description = "Avvia la re-indicizzazione. Tipo: 'conversation' per le conversazioni Claude, "
                      + "'docs' per la documentazione markdown, 'all' per entrambi. "
                      + "L'indicizzazione e' incrementale: solo file nuovi o modificati vengono processati.")
    public Map<String, Object> reindex(
            @ToolParam(description = "Tipo da re-indicizzare: conversation, docs, all") String type) {
        try {
            if ("conversation".equals(type)) {
                return chunkingService.reindexConversations();
            } else if ("docs".equals(type)) {
                return chunkingService.reindexDocs();
            } else if ("all".equals(type)) {
                Map<String, Object> result = new HashMap<>();
                result.put("conversations", chunkingService.reindexConversations());
                result.put("docs", chunkingService.reindexDocs());
                return result;
            } else {
                return Map.of("error", "Tipo non valido. Usa: conversation, docs, all");
            }
        } catch (Exception e) {
            log.error("Errore reindex: {}", e.getMessage());
            return Map.of("error", "Errore reindex: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> formatResults(List<Document> documents) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", truncateText(doc.getText(), 800));
            entry.putAll(doc.getMetadata());
            if (doc.getScore() != null) {
                entry.put("similarity", Math.round(doc.getScore() * 1000.0) / 1000.0);
            }
            results.add(entry);
        }
        return results;
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
