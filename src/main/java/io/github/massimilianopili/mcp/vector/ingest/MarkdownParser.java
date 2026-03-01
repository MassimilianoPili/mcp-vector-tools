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

@Component
public class MarkdownParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownParser.class);
    private static final int MAX_CHUNK_CHARS = 2000;
    private static final int OVERLAP_CHARS = 200;
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);

    public List<Document> parse(Path mdFile) {
        List<Document> documents = new ArrayList<>();

        try {
            String content = Files.readString(mdFile);
            String fileName = mdFile.getFileName().toString();
            String filePath = mdFile.toString();

            List<Section> sections = splitByHeadings(content);

            for (Section section : sections) {
                if (section.text.isBlank()) continue;

                String chunkText = section.heading.isEmpty()
                        ? section.text.trim()
                        : section.heading + "\n\n" + section.text.trim();

                if (chunkText.length() <= MAX_CHUNK_CHARS) {
                    documents.add(createDocument(chunkText, filePath, fileName, section.heading));
                } else {
                    String prefix = section.heading.isEmpty() ? "" : section.heading + "\n\n";
                    int availableChars = MAX_CHUNK_CHARS - prefix.length();
                    if (availableChars < 200) availableChars = 200;

                    String text = section.text.trim();
                    int pos = 0;
                    while (pos < text.length()) {
                        int end = Math.min(pos + availableChars, text.length());
                        String chunk = prefix + text.substring(pos, end).trim();
                        documents.add(createDocument(chunk, filePath, fileName, section.heading));
                        pos += availableChars - OVERLAP_CHARS;
                    }
                }
            }

        } catch (IOException e) {
            log.error("Errore parsing {}: {}", mdFile, e.getMessage());
        }

        return documents;
    }

    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);

        int lastEnd = 0;
        String lastHeading = "";

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String text = content.substring(lastEnd, matcher.start());
                sections.add(new Section(lastHeading, text));
            }
            lastHeading = matcher.group(0);
            lastEnd = matcher.end();
        }

        if (lastEnd < content.length()) {
            sections.add(new Section(lastHeading, content.substring(lastEnd)));
        }

        return sections;
    }

    private Document createDocument(String text, String filePath, String fileName, String heading) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "docs");
        metadata.put("source_file", filePath);
        metadata.put("file_name", fileName);
        metadata.put("heading", heading);
        return new Document(text, metadata);
    }

    private record Section(String heading, String text) {}
}
