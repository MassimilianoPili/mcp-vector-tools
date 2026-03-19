# MCP Vector Tools

Spring Boot starter MCP per ricerca semantica vettoriale con pgvector e embedding multi-provider (Ollama, ONNX, OpenAI). Successore di `mcp-embeddings-tools`. Maven Central: `io.github.massimilianopili:mcp-vector-tools`.

## Build

```bash
/opt/maven/bin/mvn clean compile
/opt/maven/bin/mvn clean install -Dgpg.skip=true
/opt/maven/bin/mvn clean deploy
```

Java 17+. Maven: `/opt/maven/bin/mvn`.

## Provider attivo su SOL

`qwen3-embedding:8b` (4096 dim) via Ollama (Gaia GPU proxy). Config in `docker-compose.yml` del server MCP:
- `MCP_VECTOR_PROVIDER=ollama`
- `MCP_VECTOR_OLLAMA_MODEL=qwen3-embedding:8b`
- `MCP_VECTOR_DIMENSIONS=4096`

Tool, struttura, configurazione completa e dipendenze: vedi [README.md](README.md).
Ricerca semantica: `embeddings_search_docs("mcp-vector-tools")`
