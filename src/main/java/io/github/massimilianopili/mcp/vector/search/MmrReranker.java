package io.github.massimilianopili.mcp.vector.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
public class MmrReranker {

    private static final Logger log = LoggerFactory.getLogger(MmrReranker.class);

    public record EmbeddedDocument(
            String id,
            String content,
            Map<String, Object> metadata,
            float[] embedding,
            double similarity
    ) {}

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public MmrReranker(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
                        @Qualifier("vectorEmbeddingModel") EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public float[] embedQuery(String query) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(query));
        return response.getResults().getFirst().getOutput();
    }

    /**
     * Fetch candidates from vector_store with their embedding vectors.
     * Uses pgvector cosine distance operator directly.
     */
    public List<EmbeddedDocument> fetchCandidates(float[] queryEmbedding, int candidateCount,
                                                   double threshold, String filterType) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        String sql = """
                SELECT id, content, metadata::text AS metadata_json, embedding,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM vector_store
                WHERE 1 - (embedding <=> ?::vector) >= ?""";

        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral);
        params.add(vectorLiteral);
        params.add(threshold);

        if (filterType != null && !filterType.isBlank()) {
            sql += " AND metadata->>'type' = ?";
            params.add(filterType);
        }

        sql += " ORDER BY embedding <=> ?::vector LIMIT ?";
        params.add(vectorLiteral);
        params.add(candidateCount);

        return jdbc.query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String content = rs.getString("content");
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata_json"));
            float[] embedding = parseEmbedding(rs.getString("embedding"));
            double sim = rs.getDouble("similarity");
            return new EmbeddedDocument(id, content, metadata, embedding, sim);
        }, params.toArray());
    }

    /**
     * MMR reranking: iteratively select documents maximizing
     * λ·sim(doc, query) - (1-λ)·max(sim(doc, already_selected))
     */
    public List<EmbeddedDocument> mmrRerank(List<EmbeddedDocument> candidates,
                                             float[] queryEmbedding,
                                             int k, double lambda) {
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() <= k) return candidates;

        List<EmbeddedDocument> selected = new ArrayList<>();
        Set<Integer> selectedIdx = new HashSet<>();

        // First pick: highest similarity to query
        int bestFirst = 0;
        for (int i = 1; i < candidates.size(); i++) {
            if (candidates.get(i).similarity() > candidates.get(bestFirst).similarity()) {
                bestFirst = i;
            }
        }
        selected.add(candidates.get(bestFirst));
        selectedIdx.add(bestFirst);

        // Greedy selection for remaining k-1
        while (selected.size() < k) {
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;

            for (int i = 0; i < candidates.size(); i++) {
                if (selectedIdx.contains(i)) continue;

                EmbeddedDocument candidate = candidates.get(i);
                double relevance = candidate.similarity();

                // Max similarity to any already-selected document
                double maxSimToSelected = 0.0;
                for (EmbeddedDocument sel : selected) {
                    double sim = cosineSimilarity(candidate.embedding(), sel.embedding());
                    maxSimToSelected = Math.max(maxSimToSelected, sim);
                }

                double mmrScore = lambda * relevance - (1 - lambda) * maxSimToSelected;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = i;
                }
            }

            if (bestIdx == -1) break;
            selected.add(candidates.get(bestIdx));
            selectedIdx.add(bestIdx);
        }

        return selected;
    }

    /**
     * Adaptive-k: determine optimal number of results using 3-level cutoff.
     * 1. Absolute threshold: skip below absoluteThreshold
     * 2. Relative dropoff: skip below topScore * relativeDropoff
     * 3. Gap detection: find largest gap > gapThreshold in [minK, maxK]
     */
    public int adaptiveK(List<EmbeddedDocument> candidates, int minK, int maxK,
                          double absoluteThreshold, double relativeDropoff, double gapThreshold) {
        if (candidates.isEmpty()) return 0;
        if (candidates.size() <= minK) return candidates.size();

        double topScore = candidates.getFirst().similarity();

        // Level 1+2: find how many pass absolute + relative threshold
        int thresholdK = 0;
        for (EmbeddedDocument doc : candidates) {
            if (doc.similarity() < absoluteThreshold) break;
            if (doc.similarity() < topScore * relativeDropoff) break;
            thresholdK++;
        }

        // Clamp to [minK, maxK]
        thresholdK = Math.max(minK, Math.min(thresholdK, maxK));

        // Level 3: gap detection within [minK, thresholdK]
        if (thresholdK <= minK) return minK;

        double maxGap = 0;
        int gapIdx = thresholdK;
        for (int i = minK; i < thresholdK && i < candidates.size() - 1; i++) {
            double gap = candidates.get(i).similarity() - candidates.get(i + 1).similarity();
            if (gap > maxGap && gap > gapThreshold) {
                maxGap = gap;
                gapIdx = i + 1; // cut after this gap
            }
        }

        return Math.min(gapIdx, thresholdK);
    }

    /**
     * Best-at-edges (sandwich ordering): place most relevant documents at
     * beginning and end of the list to mitigate "Lost in the Middle" effect.
     * Input ranked by MMR score. Output: best at edges, worst in middle.
     */
    public List<EmbeddedDocument> bestAtEdges(List<EmbeddedDocument> docs) {
        if (docs.size() <= 3) return docs;

        List<EmbeddedDocument> result = new ArrayList<>(docs.size());
        // Odd-indexed positions go to start (in order)
        // Even-indexed positions go to end (reversed)
        // doc[0] always first (strongest primacy bias)
        List<EmbeddedDocument> start = new ArrayList<>();
        List<EmbeddedDocument> end = new ArrayList<>();

        for (int i = 0; i < docs.size(); i++) {
            if (i % 2 == 0) {
                start.add(docs.get(i));
            } else {
                end.add(docs.get(i));
            }
        }
        Collections.reverse(end);

        result.addAll(start);
        result.addAll(end);
        return result;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            // Simple JSON parsing — metadata is a flat map
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    private float[] parseEmbedding(String vectorStr) {
        if (vectorStr == null || vectorStr.isBlank()) return new float[0];
        // pgvector format: [0.1,0.2,...,0.3]
        String clean = vectorStr.substring(1, vectorStr.length() - 1);
        String[] parts = clean.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
