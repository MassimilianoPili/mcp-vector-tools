package io.github.massimilianopili.mcp.vector.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive character splitting con gerarchia separatori.
 * Usato da MarkdownParser e ConversationParser per spezzare testo
 * in chunk semanticamente coerenti entro un limite di caratteri.
 *
 * Gerarchia separatori: paragrafi → righe → frasi → parole → caratteri.
 * I chunk troppo piccoli vengono fusi con il successivo.
 */
public final class TextSplitter {

    // Incrementare quando cambia la strategia di chunking (parametri, separatori, context enrichment).
    // Il job notturno migrerà progressivamente gli embedding con versione inferiore.
    public static final int CHUNK_VERSION = 2;

    // Token budget — conservative (works with both mxbai-embed-large 512 and qwen3-embedding 32K)
    public static final double CHARS_PER_TOKEN = 3.5;
    public static final int MAX_TOKENS = 480;
    public static final int TARGET_TOKENS = 400;
    public static final int OVERLAP_TOKENS = 60;
    public static final int CONTEXT_TOKENS = 80;

    // Limiti in caratteri (derivati dai token)
    public static final int MAX_CHUNK_CHARS = (int) (MAX_TOKENS * CHARS_PER_TOKEN);       // 1680
    public static final int TARGET_CHUNK_CHARS = (int) (TARGET_TOKENS * CHARS_PER_TOKEN); // 1400
    public static final int OVERLAP_CHARS = (int) (OVERLAP_TOKENS * CHARS_PER_TOKEN);     // 210
    public static final int CONTEXT_CHARS = (int) (CONTEXT_TOKENS * CHARS_PER_TOKEN);     // 280
    public static final int MIN_CHUNK_CHARS = TARGET_CHUNK_CHARS / 3;                     // ~467

    private static final String[] SEPARATORS = {"\n\n", "\n", ". ", " ", ""};

    private TextSplitter() {}

    /**
     * Spezza il testo in chunk di massimo maxChars caratteri,
     * rispettando confini semantici (paragrafi, frasi, parole).
     */
    public static List<String> split(String text, int maxChars) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= maxChars) return List.of(text);
        return recursiveSplit(text, maxChars, 0);
    }

    /**
     * Split con merge dei chunk troppo piccoli.
     */
    public static List<String> splitAndMerge(String text, int maxChars) {
        List<String> chunks = split(text, maxChars);
        return mergeSmallChunks(chunks, maxChars);
    }

    private static List<String> recursiveSplit(String text, int maxChars, int separatorIndex) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }

        if (separatorIndex >= SEPARATORS.length) {
            return List.of(text.substring(0, maxChars));
        }

        String separator = SEPARATORS[separatorIndex];
        List<String> parts;

        if (separator.isEmpty()) {
            parts = splitByChars(text, maxChars);
        } else {
            parts = splitBySeparator(text, separator);
        }

        if (parts.size() <= 1) {
            return recursiveSplit(text, maxChars, separatorIndex + 1);
        }

        // Raggruppa parti fino a maxChars
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (current.isEmpty()) {
                current.append(part);
            } else {
                String candidate = current + separator + part;
                if (candidate.length() <= maxChars) {
                    current.append(separator).append(part);
                } else {
                    result.add(current.toString());
                    current.setLength(0);
                    current.append(part);
                }
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        // Ricorsione su chunk ancora troppo grandi
        List<String> finalResult = new ArrayList<>();
        for (String chunk : result) {
            if (chunk.length() > maxChars) {
                finalResult.addAll(recursiveSplit(chunk, maxChars, separatorIndex + 1));
            } else {
                finalResult.add(chunk);
            }
        }

        return finalResult;
    }

    private static List<String> splitBySeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(separator, start)) != -1) {
            String part = text.substring(start, idx).trim();
            if (!part.isEmpty()) parts.add(part);
            start = idx + separator.length();
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }

    private static List<String> splitByChars(String text, int maxChars) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxChars) {
            parts.add(text.substring(i, Math.min(i + maxChars, text.length())));
        }
        return parts;
    }

    /**
     * Fonde chunk consecutivi troppo piccoli (< MIN_CHUNK_CHARS).
     */
    public static List<String> mergeSmallChunks(List<String> chunks, int maxChars) {
        if (chunks.size() <= 1) return chunks;

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : chunks) {
            if (current.isEmpty()) {
                current.append(chunk);
            } else if (current.length() + chunk.length() + 2 <= maxChars
                    && (current.length() < MIN_CHUNK_CHARS || chunk.length() < MIN_CHUNK_CHARS)) {
                current.append("\n\n").append(chunk);
            } else {
                merged.add(current.toString());
                current.setLength(0);
                current.append(chunk);
            }
        }

        if (!current.isEmpty()) {
            merged.add(current.toString());
        }

        return merged;
    }
}
