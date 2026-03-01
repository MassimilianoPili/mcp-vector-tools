package io.github.massimilianopili.mcp.vector;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
@EnableConfigurationProperties(VectorProperties.class)
public class VectorConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorConfig.class);

    @Bean("vectorDataSource")
    public DataSource vectorDataSource(VectorProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getDbUrl());
        ds.setUsername(props.getDbUsername());
        ds.setPassword(props.getDbCredential());
        ds.setMaximumPoolSize(3);
        ds.setPoolName("vector-pool");
        log.info("Vector DataSource: {}", props.getDbUrl());
        return ds;
    }

    @Bean("vectorVectorStore")
    public VectorStore vectorVectorStore(
            @Qualifier("vectorDataSource") DataSource dataSource,
            VectorProperties props) {

        EmbeddingModel embeddingModel = createEmbeddingModel(props);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        VectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(props.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
        log.info("PgVectorStore: {} dim, COSINE_DISTANCE, HNSW index, provider={}",
                props.getDimensions(), props.getProvider());
        return store;
    }

    @Bean("vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(
            @Qualifier("vectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private EmbeddingModel createEmbeddingModel(VectorProperties props) {
        String provider = props.getProvider();
        return switch (provider) {
            case "ollama" -> {
                log.info("Embedding provider: Ollama ({}), model: {}",
                        props.getOllamaBaseUrl(), props.getOllamaModel());
                OllamaApi api = OllamaApi.builder()
                        .baseUrl(props.getOllamaBaseUrl())
                        .build();
                yield OllamaEmbeddingModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(OllamaOptions.builder()
                                .model(props.getOllamaModel())
                                .build())
                        .build();
            }
            case "onnx" -> {
                log.info("Embedding provider: ONNX (all-MiniLM-L6-v2, in-process)");
                TransformersEmbeddingModel m = new TransformersEmbeddingModel();
                if (props.getOnnxModelCacheDir() != null && !props.getOnnxModelCacheDir().isBlank()) {
                    m.setResourceCacheDirectory(props.getOnnxModelCacheDir());
                    log.info("ONNX model cache: {}", props.getOnnxModelCacheDir());
                }
                try {
                    m.afterPropertiesSet();
                } catch (Exception e) {
                    throw new RuntimeException("Inizializzazione modello ONNX fallita", e);
                }
                yield m;
            }
            case "openai" -> {
                log.info("Embedding provider: OpenAI-compatible ({}), model: {}",
                        props.getOpenaiBaseUrl(), props.getOpenaiModel());
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(props.getOpenaiBaseUrl())
                        .apiKey(props.getOpenaiApiKey())
                        .build();
                yield new OpenAiEmbeddingModel(api,
                        MetadataMode.EMBED,
                        OpenAiEmbeddingOptions.builder()
                                .model(props.getOpenaiModel())
                                .build());
            }
            default -> throw new IllegalArgumentException(
                    "Provider sconosciuto: " + provider +
                    ". Valori ammessi: ollama, onnx, openai");
        };
    }
}
