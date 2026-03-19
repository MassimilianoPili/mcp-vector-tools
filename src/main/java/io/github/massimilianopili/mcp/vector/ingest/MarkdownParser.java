package io.github.massimilianopili.mcp.vector.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown parser con recursive character splitting e context enrichment.
 * Delega il splitting a {@link TextSplitter}.
 *
 * Responsabilità di questo parser:
 * - Struttura markdown (heading → albero di sezioni con breadcrumb)
 * - Code fence protection (no split dentro ```)
 * - Context enrichment: prepend [File: ...] [Sezione: breadcrumb]
 */
@Component
public class MarkdownParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownParser.class);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    public List<Document> parse(Path mdFile) {
        List<Document> documents = new ArrayList<>();

        try {
            String content = Files.readString(mdFile);
            String fileName = mdFile.getFileName().toString();
            String filePath = mdFile.toString();

            List<Section> sections = splitByHeadings(content);

            int chunkIndex = 0;
            for (Section section : sections) {
                if (section.text.isBlank()) continue;

                String contextPrefix = buildContextPrefix(fileName, section.headerPath);
                int availableChars = TextSplitter.MAX_CHUNK_CHARS - contextPrefix.length();
                if (availableChars < TextSplitter.MIN_CHUNK_CHARS)
                    availableChars = TextSplitter.MIN_CHUNK_CHARS;

                List<String> chunks = TextSplitter.splitAndMerge(section.text.trim(), availableChars);

                for (String chunk : chunks) {
                    String enrichedText = contextPrefix + chunk;
                    documents.add(createDocument(enrichedText, filePath, fileName,
                            section.heading, section.headerPath, chunkIndex));
                    chunkIndex++;
                }
            }

        } catch (IOException e) {
            log.error("Errore parsing {}: {}", mdFile, e.getMessage());
        }

        return documents;
    }

    private String buildContextPrefix(String fileName, String headerPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("[File: ").append(fileName).append("]\n");
        if (headerPath != null && !headerPath.isEmpty()) {
            String path = headerPath.length() > TextSplitter.CONTEXT_CHARS - 30
                    ? headerPath.substring(0, TextSplitter.CONTEXT_CHARS - 33) + "..."
                    : headerPath;
            sb.append("[Sezione: ").append(path).append("]\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private List<Section> splitByHeadings(String content) {
        String safeContent = protectCodeFences(content);

        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(safeContent);

        String[] headingStack = new String[7];
        int lastEnd = 0;
        String lastHeading = "";
        int lastLevel = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String text = content.substring(lastEnd, Math.min(matcher.start(), content.length()));
                sections.add(new Section(lastHeading, buildHeaderPath(headingStack, lastLevel), text));
            }

            int level = matcher.group(1).length();
            String headingText = matcher.group(2).trim();
            lastHeading = headingText;
            lastLevel = level;

            headingStack[level] = headingText;
            for (int i = level + 1; i < headingStack.length; i++) {
                headingStack[i] = null;
            }

            lastEnd = matcher.end();
        }

        if (lastEnd < content.length()) {
            String text = content.substring(lastEnd);
            sections.add(new Section(lastHeading, buildHeaderPath(headingStack, lastLevel), text));
        }

        if (sections.isEmpty() && !content.isBlank()) {
            sections.add(new Section("", "", content));
        }

        return sections;
    }

    private String protectCodeFences(String content) {
        StringBuilder sb = new StringBuilder();
        boolean inCodeBlock = false;
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                sb.append(line);
            } else if (inCodeBlock && line.startsWith("#")) {
                sb.append("\u0000").append(line.substring(1));
            } else {
                sb.append(line);
            }
            sb.append("\n");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n' && !content.endsWith("\n")) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String buildHeaderPath(String[] stack, int currentLevel) {
        if (currentLevel == 0) return "";
        StringBuilder path = new StringBuilder();
        for (int i = 1; i <= currentLevel; i++) {
            if (stack[i] != null) {
                if (!path.isEmpty()) path.append(" > ");
                path.append(stack[i]);
            }
        }
        return path.toString();
    }

    private Document createDocument(String text, String filePath, String fileName,
                                     String heading, String headerPath, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "docs");
        metadata.put("source_file", filePath);
        metadata.put("file_name", fileName);
        metadata.put("heading", heading);
        metadata.put("header_path", headerPath);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("chunk_version", TextSplitter.CHUNK_VERSION);
        return new Document(text, metadata);
    }

    private record Section(String heading, String headerPath, String text) {}
}
