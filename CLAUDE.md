# MCP Vector Tools

Spring Boot starter che fornisce tool MCP per ricerca semantica vettoriale con pgvector e embedding multi-provider (Ollama, ONNX, OpenAI). Successore di `mcp-embeddings-tools`, con supporto provider multipli. Pubblicato su Maven Central come `io.github.massimilianopili:mcp-vector-tools`.

## Build

```bash
# Build
/opt/maven/bin/mvn clean compile

# Install locale (senza GPG)
/opt/maven/bin/mvn clean install -Dgpg.skip=true

# Deploy su Maven Central
/opt/maven/bin/mvn clean deploy
```

Java 17+ richiesto. Maven: `/opt/maven/bin/mvn`.

## Struttura Progetto

```
src/main/java/io/github/massimilianopili/mcp/vector/
├── VectorProperties.java              # @ConfigurationProperties(prefix = "mcp.vector")
├── VectorConfig.java                  # DataSource, EmbeddingModel factory, PgVectorStore
├── VectorToolsAutoConfiguration.java  # Spring Boot auto-config
├── VectorTools.java                   # @Tool: ricerca semantica, stats, reindex
└── ingest/
    ├── ChunkingService.java           # Splitting documenti in chunk per VectorStore
    ├── ConversationParser.java        # Parser JSONL conversazioni Claude Code
    ├── MarkdownParser.java            # Parser documenti .md (split per heading)
    └── SyncTracker.java               # Tracking indicizzazione incrementale (last_modified)

src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## Tool (5 totali)

- `embeddings_search` — Ricerca semantica generica con filtri (type: conversation/docs), topK (max 20, default 5), threshold (0.0-1.0, default 0.7)
- `embeddings_search_conversations` — Cerca nelle conversazioni Claude Code indicizzate
- `embeddings_search_docs` — Cerca nella documentazione infrastruttura (.md, CLAUDE.md, docs/)
- `embeddings_stats` — Statistiche: conteggio per tipo, file, chunk totali, ultimo aggiornamento
- `embeddings_reindex` — Re-indicizza: 'conversation' | 'docs' | 'all'. Solo incrementale (file modificati)

## Provider Embedding

| Provider | Modello default | Dimensioni | Note |
|----------|----------------|------------|------|
| **ollama** (default) | nomic-embed-text | 768 | Locale via Ollama server, nessun costo |
| **onnx** | all-MiniLM-L6-v2 | 384 | In-process, nessun server esterno |
| **openai** | text-embedding-3-small | 1536 | API remota, richiede API key |

Il provider si seleziona con `mcp.vector.provider`. Le dimensioni si adattano automaticamente ma possono essere sovrascritte con `mcp.vector.dimensions`.

## Pattern Chiave

- **@Tool** (Spring AI): metodi sincroni. I tool usano `@Tool` (non `@ReactiveTool`) perche' le operazioni di ricerca e indicizzazione sono bloccanti.
- **Attivazione**: `@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")`.
- **PgVectorStore**: PostgreSQL + pgvector, COSINE_DISTANCE, indice HNSW, schema auto-inizializzato.
- **EmbeddingModel factory**: `VectorConfig.createEmbeddingModel()` seleziona il provider in base alla property `mcp.vector.provider`.
- **Indicizzazione incrementale**: `SyncTracker` traccia file path + last_modified nella tabella `embeddings_sync`. Solo i file modificati vengono re-indicizzati.
- **Pipeline ingest**: ConversationParser (JSONL → turni user+assistant, skip thinking/tool_use) e MarkdownParser (split per heading ##/###). Batch da 50 doc per `VectorStore.add()`.

## Configurazione

```properties
# Obbligatoria — abilita tutti i tool
MCP_VECTOR_ENABLED=true

# Provider (default: ollama)
MCP_VECTOR_PROVIDER=ollama              # ollama | onnx | openai
MCP_VECTOR_DIMENSIONS=768               # default dipende dal provider

# Database pgvector
MCP_VECTOR_DB_URL=jdbc:postgresql://localhost:5432/embeddings
MCP_VECTOR_DB_USER=postgres
MCP_VECTOR_DB_CREDENTIAL=password

# Path sorgenti da indicizzare
MCP_VECTOR_CONVERSATIONS_PATH=/path/to/claude/projects
MCP_VECTOR_DOCS_PATH=/path/to/docs

# Provider: Ollama
MCP_VECTOR_OLLAMA_BASE_URL=http://ollama:11434
MCP_VECTOR_OLLAMA_MODEL=nomic-embed-text

# Provider: ONNX (in-process, nessun server)
MCP_VECTOR_ONNX_MODEL_CACHE=/tmp/onnx-models

# Provider: OpenAI-compatible
MCP_VECTOR_OPENAI_BASE_URL=https://api.openai.com
MCP_VECTOR_OPENAI_API_KEY=sk-...
MCP_VECTOR_OPENAI_MODEL=text-embedding-3-small
```

## Migrazione da mcp-embeddings-tools

| Vecchio (mcp.embeddings.*) | Nuovo (mcp.vector.*) |
|---------------------------|---------------------|
| `mcp.embeddings.enabled` | `mcp.vector.enabled` |
| `mcp.embeddings.db-url` | `mcp.vector.db-url` |
| `mcp.embeddings.db-username` | `mcp.vector.db-username` |
| `mcp.embeddings.db-credential` | `mcp.vector.db-credential` |
| `mcp.embeddings.model-cache-dir` | `mcp.vector.onnx-model-cache-dir` |
| — | `mcp.vector.provider` (NUOVO) |
| — | `mcp.vector.dimensions` (NUOVO) |
| — | `mcp.vector.ollama-*` (NUOVO) |
| — | `mcp.vector.openai-*` (NUOVO) |

## Dipendenze

- Spring Boot 3.4.1 (spring-boot-autoconfigure)
- Spring AI 1.0.0 (spring-ai-pgvector-store, spring-ai-transformers, spring-ai-ollama, spring-ai-openai)
- spring-ai-reactive-tools 0.2.0
- PostgreSQL + pgvector

## Maven Central

- GroupId: `io.github.massimilianopili`
- Plugin: `central-publishing-maven-plugin` v0.7.0
- Credenziali: Central Portal token in `~/.m2/settings.xml` (server id: `central`)
