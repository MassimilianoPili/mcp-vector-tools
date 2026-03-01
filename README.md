# MCP Vector Tools

Spring Boot starter providing MCP tools for semantic vector search with pgvector and multi-provider embeddings (Ollama, ONNX, OpenAI). Indexes Claude Code conversations and Markdown documentation for retrieval-augmented generation.

## Installation

```xml
<dependency>
    <groupId>io.github.massimilianopili</groupId>
    <artifactId>mcp-vector-tools</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 21+ and Spring AI 1.0.0+.

## Tools

| Tool | Description |
|------|-------------|
| `embeddings_search` | Semantic search with filters (type: conversation/docs), topK (max 20), threshold (0.0-1.0) |
| `embeddings_search_conversations` | Search indexed Claude Code conversations |
| `embeddings_search_docs` | Search indexed infrastructure documentation (.md files) |
| `embeddings_stats` | Statistics: count by type, files, total chunks, last update |
| `embeddings_reindex` | Re-index: 'conversation' \| 'docs' \| 'all' (incremental, modified files only) |

## Embedding Providers

| Provider | Default Model | Dimensions | Notes |
|----------|--------------|------------|-------|
| **ollama** (default) | nomic-embed-text | 768 | Local via Ollama server, zero cost |
| **onnx** | all-MiniLM-L6-v2 | 384 | In-process, no external server |
| **openai** | text-embedding-3-small | 1536 | Remote API, requires API key |

## Configuration

```properties
# Required — enables all tools
MCP_VECTOR_ENABLED=true

# Provider (default: ollama)
MCP_VECTOR_PROVIDER=ollama              # ollama | onnx | openai

# Database (PostgreSQL + pgvector)
MCP_VECTOR_DB_URL=jdbc:postgresql://localhost:5432/embeddings
MCP_VECTOR_DB_USER=postgres
MCP_VECTOR_DB_CREDENTIAL=password

# Source paths
MCP_VECTOR_CONVERSATIONS_PATH=/path/to/claude/projects
MCP_VECTOR_DOCS_PATH=/path/to/docs

# Provider: Ollama
MCP_VECTOR_OLLAMA_BASE_URL=http://ollama:11434
MCP_VECTOR_OLLAMA_MODEL=nomic-embed-text

# Provider: ONNX
MCP_VECTOR_ONNX_MODEL_CACHE=/tmp/onnx-models

# Provider: OpenAI-compatible
MCP_VECTOR_OPENAI_BASE_URL=https://api.openai.com
MCP_VECTOR_OPENAI_API_KEY=sk-...
MCP_VECTOR_OPENAI_MODEL=text-embedding-3-small
```

## How It Works

- Uses `@Tool` (Spring AI) for synchronous MCP tool methods
- Auto-configured via `VectorToolsAutoConfiguration` with `@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")`
- PgVectorStore with COSINE_DISTANCE similarity and HNSW index (auto-initialized schema)
- Incremental indexing: `SyncTracker` tracks file paths and `last_modified` timestamps
- Ingest pipeline: `ConversationParser` (JSONL conversations) + `MarkdownParser` (split by heading)

## Requirements

- Java 21+
- Spring Boot 3.4+
- Spring AI 1.0.0+
- PostgreSQL with pgvector extension

## License

[MIT License](LICENSE)
