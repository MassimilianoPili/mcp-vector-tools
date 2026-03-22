package io.github.massimilianopili.mcp.vector.ingest;

import org.treesitter.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * AST-aware code parser for embedding. Uses tree-sitter (bonede) to parse source files
 * and produce Document chunks aligned to semantic boundaries (functions, classes, methods).
 *
 * Instantiated as @Bean in VectorToolsAutoConfiguration (not @Component)
 * because tree-sitter JNI may fail to load in some environments.
 */
public class CodeParser {

    private static final Logger log = LoggerFactory.getLogger(CodeParser.class);
    private static final int MAX_CHUNK_CHARS = 1680;

    private static final Map<String, Supplier<TSLanguage>> EXTENSION_MAP = Map.ofEntries(
            Map.entry(".java", TreeSitterJava::new),
            Map.entry(".go", TreeSitterGo::new),
            Map.entry(".py", TreeSitterPython::new),
            Map.entry(".ts", TreeSitterTypescript::new),
            Map.entry(".tsx", TreeSitterTypescript::new),
            Map.entry(".js", TreeSitterJavascript::new),
            Map.entry(".jsx", TreeSitterJavascript::new),
            Map.entry(".c", TreeSitterC::new),
            Map.entry(".h", TreeSitterC::new),
            Map.entry(".rs", TreeSitterRust::new)
    );

    private static final Map<String, Set<String>> CLASS_TYPES = Map.of(
            "java", Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration"),
            "go", Set.of("type_spec"),
            "python", Set.of("class_definition"),
            "typescript", Set.of("class_declaration", "interface_declaration"),
            "javascript", Set.of("class_declaration"),
            "c", Set.of("struct_specifier"),
            "rust", Set.of("struct_item", "impl_item", "trait_item", "enum_item")
    );

    private static final Map<String, Set<String>> FUNCTION_TYPES = Map.of(
            "java", Set.of("method_declaration", "constructor_declaration"),
            "go", Set.of("function_declaration", "method_declaration"),
            "python", Set.of("function_definition"),
            "typescript", Set.of("function_declaration", "method_definition"),
            "javascript", Set.of("function_declaration", "method_definition"),
            "c", Set.of("function_definition"),
            "rust", Set.of("function_item")
    );

    public List<Document> parse(Path sourceFile) {
        TSLanguage lang = detectLanguage(sourceFile);
        if (lang == null) return List.of();

        String langName = detectLangName(sourceFile);
        List<Document> documents = new ArrayList<>();

        try {
            String source = Files.readString(sourceFile);
            String filePath = sourceFile.toString();
            String fileName = sourceFile.getFileName().toString();

            TSParser parser = new TSParser();
            parser.setLanguage(lang);
            TSTree tree = parser.parseString(null, source);
            TSNode root = tree.getRootNode();

            int chunkIndex = 0;
            chunkIndex = walkForChunks(root, source, langName, filePath, fileName,
                    null, documents, chunkIndex);

            // If no symbols found (e.g. script file), chunk the whole file
            if (documents.isEmpty() && !source.isBlank()) {
                String prefix = buildPrefix(fileName, langName, null, null);
                List<String> chunks = splitByLines(source, MAX_CHUNK_CHARS - prefix.length());
                for (String chunk : chunks) {
                    documents.add(createDocument(prefix + chunk, filePath, fileName,
                            langName, null, null, 1, source.split("\n").length, chunkIndex++));
                }
            }
        } catch (IOException e) {
            log.error("Failed to read {}: {}", sourceFile, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to parse {}: {}", sourceFile, e.getMessage());
        }

        return documents;
    }

    private int walkForChunks(TSNode node, String source, String langName,
                               String filePath, String fileName,
                               String parentClass, List<Document> documents, int chunkIndex) {
        String nodeType = node.getType();
        Set<String> classDefs = CLASS_TYPES.getOrDefault(langName, Set.of());
        Set<String> funcDefs = FUNCTION_TYPES.getOrDefault(langName, Set.of());

        if (classDefs.contains(nodeType)) {
            String className = extractName(node, source);
            for (int i = 0; i < node.getChildCount(); i++) {
                chunkIndex = walkForChunks(node.getChild(i), source, langName,
                        filePath, fileName, className, documents, chunkIndex);
            }

            String classText = extractClassHeader(node, source);
            if (!classText.isBlank()) {
                String prefix = buildPrefix(fileName, langName, className, null);
                int startLine = node.getStartPoint().getRow() + 1;
                int endLine = node.getEndPoint().getRow() + 1;
                documents.add(createDocument(prefix + classText, filePath, fileName,
                        langName, className, null, startLine, endLine, chunkIndex++));
            }
            return chunkIndex;
        }

        if (funcDefs.contains(nodeType)) {
            String funcName = extractName(node, source);
            String text = getNodeText(node, source);
            int startLine = node.getStartPoint().getRow() + 1;
            int endLine = node.getEndPoint().getRow() + 1;

            String withComment = includeLeadingComment(node, source, text);

            String prefix = buildPrefix(fileName, langName, parentClass, funcName);
            int available = MAX_CHUNK_CHARS - prefix.length();

            if (withComment.length() <= available) {
                documents.add(createDocument(prefix + withComment, filePath, fileName,
                        langName, parentClass, funcName, startLine, endLine, chunkIndex++));
            } else {
                List<String> chunks = splitByLines(withComment, available);
                for (String chunk : chunks) {
                    documents.add(createDocument(prefix + chunk, filePath, fileName,
                            langName, parentClass, funcName, startLine, endLine, chunkIndex++));
                }
            }
            return chunkIndex;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            chunkIndex = walkForChunks(node.getChild(i), source, langName,
                    filePath, fileName, parentClass, documents, chunkIndex);
        }
        return chunkIndex;
    }

    private String buildPrefix(String fileName, String langName, String className, String funcName) {
        StringBuilder sb = new StringBuilder();
        sb.append("[File: ").append(fileName).append("]\n");
        sb.append("[Language: ").append(langName).append("]\n");
        if (className != null) sb.append("[Class: ").append(className).append("]\n");
        if (funcName != null) sb.append("[Method: ").append(funcName).append("]\n");
        sb.append("\n");
        return sb.toString();
    }

    private Document createDocument(String text, String filePath, String fileName,
                                     String langName, String className, String funcName,
                                     int startLine, int endLine, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "code");
        metadata.put("language", langName);
        metadata.put("source_file", filePath);
        metadata.put("file_name", fileName);
        if (className != null) metadata.put("class_name", className);
        if (funcName != null) metadata.put("function_name", funcName);
        metadata.put("start_line", startLine);
        metadata.put("end_line", endLine);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("chunk_version", TextSplitter.CHUNK_VERSION);
        return new Document(text, metadata);
    }

    private String extractName(TSNode node, String source) {
        // Pass 1: prefer field_identifier (Go method names) over identifier (receiver names)
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            String t = child.getType();
            if (t.equals("field_identifier") || t.equals("name")) {
                return getNodeText(child, source);
            }
        }
        // Pass 2: fallback to identifier, type_identifier, property_identifier
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            String t = child.getType();
            if (t.equals("identifier") || t.equals("type_identifier")
                    || t.equals("property_identifier")) {
                return getNodeText(child, source);
            }
        }
        return "anonymous";
    }

    private String extractClassHeader(TSNode node, String source) {
        String text = getNodeText(node, source);
        int braceIdx = text.indexOf('{');
        if (braceIdx > 0) {
            return text.substring(0, braceIdx).trim();
        }
        String[] lines = text.split("\n", 3);
        return lines.length >= 2 ? lines[0] + "\n" + lines[1] : lines[0];
    }

    private String includeLeadingComment(TSNode node, String source, String nodeText) {
        int nodeStart = node.getStartByte();
        if (nodeStart <= 0) return nodeText;

        String before = source.substring(Math.max(0, nodeStart - 500), nodeStart);
        String[] lines = before.split("\n");

        List<String> commentLines = new ArrayList<>();
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/**")
                    || trimmed.startsWith("*/") || trimmed.startsWith("#")
                    || trimmed.startsWith("///") || trimmed.startsWith("\"\"\"")) {
                commentLines.add(0, lines[i]);
            } else if (trimmed.isEmpty()) {
                continue;
            } else {
                break;
            }
        }

        if (commentLines.isEmpty()) return nodeText;
        return String.join("\n", commentLines) + "\n" + nodeText;
    }

    private List<String> splitByLines(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > maxChars && !current.isEmpty()) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append("\n");
            current.append(line);
        }
        if (!current.isEmpty()) chunks.add(current.toString());

        return chunks;
    }

    private String getNodeText(TSNode node, String source) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (start >= 0 && end <= bytes.length && start < end) {
            return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    private TSLanguage detectLanguage(Path file) {
        var supplier = EXTENSION_MAP.get(getExtension(file));
        return supplier != null ? supplier.get() : null;
    }

    private String detectLangName(Path file) {
        String ext = getExtension(file);
        return switch (ext) {
            case ".java" -> "java";
            case ".go" -> "go";
            case ".py" -> "python";
            case ".ts", ".tsx" -> "typescript";
            case ".js", ".jsx" -> "javascript";
            case ".c", ".h" -> "c";
            case ".rs" -> "rust";
            default -> "unknown";
        };
    }

    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
