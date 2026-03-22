package io.github.massimilianopili.mcp.vector;

import io.github.massimilianopili.mcp.vector.ingest.ChunkingService;
import io.github.massimilianopili.mcp.vector.ingest.CodeParser;
import io.github.massimilianopili.mcp.vector.ingest.ConversationParser;
import io.github.massimilianopili.mcp.vector.ingest.MarkdownParser;
import io.github.massimilianopili.mcp.vector.ingest.SyncTracker;
import io.github.massimilianopili.mcp.vector.search.MmrReranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
@Import({VectorConfig.class, VectorTools.class, MmrReranker.class,
         ChunkingService.class, ConversationParser.class, MarkdownParser.class,
         SyncTracker.class})
public class VectorToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorToolsAutoConfiguration.class);

    @Bean("vectorCodeParser")
    @ConditionalOnMissingBean(CodeParser.class)
    public CodeParser vectorCodeParser() {
        try {
            CodeParser parser = new CodeParser();
            log.info("CodeParser initialized (tree-sitter JNI loaded)");
            return parser;
        } catch (UnsatisfiedLinkError | Exception e) {
            log.warn("CodeParser unavailable (tree-sitter JNI failed: {}). Code embedding disabled.", e.getMessage());
            return null;
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "vectorToolCallbackProvider")
    public ToolCallbackProvider vectorToolCallbackProvider(VectorTools vectorTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(vectorTools)
                .build();
    }
}
