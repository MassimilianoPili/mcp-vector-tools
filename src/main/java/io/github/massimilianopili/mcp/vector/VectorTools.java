package io.github.massimilianopili.mcp.vector;

import io.github.massimilianopili.mcp.vector.ingest.ChunkingService;
import io.github.massimilianopili.mcp.vector.ingest.SyncTracker;
import io.github.massimilianopili.mcp.vector.search.MmrReranker;
import io.github.massimilianopili.mcp.vector.search.MmrReranker.EmbeddedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
public class VectorTools {

    private static final Logger log = LoggerFactory.getLogger(VectorTools.class);

    private final MmrReranker mmrReranker;
    private final VectorProperties props;
    private final ChunkingService chunkingService;
    private final SyncTracker syncTracker;

    public VectorTools(
            MmrReranker mmrReranker,
            VectorProperties props,
            ChunkingService chunkingService,
            SyncTracker syncTracker) {
        this.mmrReranker = mmrReranker;
        this.props = props;
        this.chunkingService = chunkingService;
        this.syncTracker = syncTracker;
    }

    @Tool(name = "embeddings_search",
          description = "Semantic search in the vector store with MMR reranking for diversity. "
                      + "Finds documents similar to the provided text, eliminating redundancy. "
                      + "Supports type filters (conversation, docs). Uses adaptive-k to optimize result count.")
    public List<Map<String, Object>> search(
            @ToolParam(description = "Text to search semantically") String query,
            @ToolParam(description = "Maximum number of results (default 5, max 20)", required = false) Integer topK,
            @ToolParam(description = "Filter by type: conversation, docs (if omitted searches all)", required = false) String type,
            @ToolParam(description = "Minimum similarity threshold 0.0-1.0 (default 0.7)", required = false) Double threshold) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
            double th = (threshold != null && threshold > 0) ? threshold : 0.7;

            return mmrSearch(query, k, th, type);
        } catch (Exception e) {
            log.error("Errore ricerca: {}", e.getMessage());
            return List.of(Map.of("error", "Errore ricerca: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_search_conversations",
          description = "Semantic search in Claude Code conversations with MMR for diversity. "
                      + "Returns chunk text, session_id, turn, and source file.")
    public List<Map<String, Object>> searchConversations(
            @ToolParam(description = "Text to search in conversations") String query,
            @ToolParam(description = "Maximum number of results (default 5)", required = false) Integer topK) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
            return mmrSearch(query, k, 0.65, "conversation");
        } catch (Exception e) {
            log.error("Errore ricerca conversazioni: {}", e.getMessage());
            return List.of(Map.of("error", "Errore: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_search_docs",
          description = "Semantic search in infrastructure documentation (CLAUDE.md, README, docs/) with MMR. "
                      + "Returns relevant sections with file_path and heading, diversified by content.")
    public List<Map<String, Object>> searchDocs(
            @ToolParam(description = "Text to search in documentation") String query,
            @ToolParam(description = "Maximum number of results (default 5)", required = false) Integer topK) {
        try {
            int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
            return mmrSearch(query, k, 0.65, "docs");
        } catch (Exception e) {
            log.error("Errore ricerca docs: {}", e.getMessage());
            return List.of(Map.of("error", "Errore: " + e.getMessage()));
        }
    }

    @Tool(name = "embeddings_stats",
          description = "Shows statistics on indexed embeddings: "
                      + "count by type, number of files and chunks, last update. "
                      + "Includes chunk_versioning with current version, stale files to migrate, "
                      + "and distribution by version. Also includes ongoing reindex status (if active).")
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
            result.put("chunk_versioning", syncTracker.getVersionStats());
            result.putAll(chunkingService.getReindexStatus());
            return result;
        } catch (Exception e) {
            return Map.of("error", "Errore statistiche: " + e.getMessage());
        }
    }

    @Tool(name = "embeddings_reindex",
          description = "Starts background re-indexing. Type: 'conversation' for Claude conversations, "
                      + "'docs' for markdown documentation, 'all' for both. "
                      + "Indexing is incremental (only new/modified files) and asynchronous: "
                      + "returns immediately with status 'started'. Use embeddings_stats to monitor progress.")
    public Map<String, Object> reindex(
            @ToolParam(description = "Type to re-index: conversation, docs, all") String type) {
        if (type == null || (!type.equals("conversation") && !type.equals("docs") && !type.equals("all"))) {
            return Map.of("error", "Tipo non valido. Usa: conversation, docs, all");
        }
        return chunkingService.reindexAsync(type);
    }

    /**
     * Full MMR pipeline: embed → fetch candidates → adaptive-k → MMR rerank → best-at-edges.
     */
    private List<Map<String, Object>> mmrSearch(String query, int requestedK,
                                                  double threshold, String filterType) {
        float[] queryEmbedding = mmrReranker.embedQuery(query);

        // Fetch large candidate set (5:1 ratio, minimum candidateCount from config)
        int candidateCount = Math.max(requestedK * 5, props.getMmrCandidateCount());
        List<EmbeddedDocument> candidates = mmrReranker.fetchCandidates(
                queryEmbedding, candidateCount, threshold, filterType);

        if (candidates.isEmpty()) return List.of();

        // Adaptive-k: determine optimal result count
        int k = mmrReranker.adaptiveK(candidates,
                Math.min(props.getAdaptiveKMinK(), requestedK),
                requestedK,
                props.getAdaptiveKAbsoluteThreshold(),
                props.getAdaptiveKRelativeDropoff(),
                props.getAdaptiveKGapThreshold());

        // MMR rerank for diversity
        List<EmbeddedDocument> reranked = mmrReranker.mmrRerank(
                candidates, queryEmbedding, k, props.getMmrLambda());

        // Best-at-edges ordering (mitigate "Lost in the Middle")
        List<EmbeddedDocument> ordered = mmrReranker.bestAtEdges(reranked);

        log.debug("MMR search: {} candidates → adaptive-k={} → {} results (lambda={})",
                candidates.size(), k, ordered.size(), props.getMmrLambda());

        return formatResults(ordered);
    }

    private List<Map<String, Object>> formatResults(List<EmbeddedDocument> documents) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (EmbeddedDocument doc : documents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", truncateText(doc.content(), 800));
            entry.putAll(doc.metadata());
            entry.put("similarity", Math.round(doc.similarity() * 1000.0) / 1000.0);
            results.add(entry);
        }
        return results;
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
