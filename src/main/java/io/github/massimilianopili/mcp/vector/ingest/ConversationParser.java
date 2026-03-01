package io.github.massimilianopili.mcp.vector.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(ConversationParser.class);
    private static final int MAX_CHUNK_CHARS = 2000;
    private static final int OVERLAP_CHARS = 200;
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Document> parse(Path jsonlFile) {
        List<Document> documents = new ArrayList<>();
        String fileName = jsonlFile.getFileName().toString();
        String sessionId = fileName.replace(".jsonl", "");

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile)) {
            List<JsonNode> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    lines.add(mapper.readTree(line));
                } catch (Exception e) {
                    // skip righe malformate
                }
            }

            String currentQuestion = null;
            StringBuilder currentAnswer = new StringBuilder();
            int turnIndex = 0;

            for (JsonNode node : lines) {
                String type = node.has("type") ? node.get("type").asText() : "";

                if ("user".equals(type)) {
                    if (currentQuestion != null && !currentAnswer.isEmpty()) {
                        documents.addAll(createTurnDocuments(
                                currentQuestion, currentAnswer.toString(),
                                sessionId, fileName, jsonlFile.toString(), turnIndex));
                        turnIndex++;
                    }
                    currentQuestion = extractText(node);
                    currentAnswer.setLength(0);

                } else if ("assistant".equals(type)) {
                    String text = extractText(node);
                    if (text != null && !text.isBlank()) {
                        if (!currentAnswer.isEmpty()) currentAnswer.append("\n\n");
                        currentAnswer.append(text);
                    }
                }
            }

            if (currentQuestion != null && !currentAnswer.isEmpty()) {
                documents.addAll(createTurnDocuments(
                        currentQuestion, currentAnswer.toString(),
                        sessionId, fileName, jsonlFile.toString(), turnIndex));
            }

        } catch (IOException e) {
            log.error("Errore parsing {}: {}", jsonlFile, e.getMessage());
        }

        return documents;
    }

    private String extractText(JsonNode node) {
        JsonNode message = node.get("message");
        if (message == null) return null;
        JsonNode content = message.get("content");
        if (content == null) return null;

        if (content.isTextual()) {
            String text = content.asText();
            return text.isBlank() ? null : text;
        }

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item.isObject()) {
                    String itemType = item.has("type") ? item.get("type").asText() : "";
                    if ("text".equals(itemType) && item.has("text")) {
                        String text = item.get("text").asText();
                        if (!text.isBlank()) {
                            if (!sb.isEmpty()) sb.append("\n\n");
                            sb.append(text);
                        }
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }

        return null;
    }

    private List<Document> createTurnDocuments(
            String question, String answer,
            String sessionId, String fileName, String filePath, int turnIndex) {

        List<Document> docs = new ArrayList<>();
        String fullText = "DOMANDA: " + question.trim() + "\n\nRISPOSTA: " + answer.trim();

        if (fullText.length() <= MAX_CHUNK_CHARS) {
            docs.add(createDocument(fullText, sessionId, fileName, filePath, turnIndex, 0));
        } else {
            String prefix = "DOMANDA: " + truncate(question.trim(), 300) + "\n\nRISPOSTA: ";
            int availableChars = MAX_CHUNK_CHARS - prefix.length();
            if (availableChars < 200) availableChars = 200;

            int subIndex = 0;
            int pos = 0;
            while (pos < answer.length()) {
                int end = Math.min(pos + availableChars, answer.length());
                String chunk = prefix + answer.substring(pos, end).trim();
                docs.add(createDocument(chunk, sessionId, fileName, filePath, turnIndex, subIndex));
                pos += availableChars - OVERLAP_CHARS;
                subIndex++;
            }
        }

        return docs;
    }

    private Document createDocument(String text, String sessionId, String fileName,
                                     String filePath, int turnIndex, int subIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "conversation");
        metadata.put("session_id", sessionId);
        metadata.put("source_file", filePath);
        metadata.put("turn_index", turnIndex);
        metadata.put("sub_index", subIndex);
        return new Document(text, metadata);
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
