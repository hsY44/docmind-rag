# PLANNING.md
Single source of truth for scope, architecture, and specs. Read this before implementing anything.

## Goal
Build an Agentic RAG service on Spring AI: ingest documents from `docmind-mcp-server`,
index them into pgvector, and answer questions with retrieval-grounded responses.
When vector retrieval is insufficient, the LLM can fall back to calling the MCP server's
keyword-search tools directly (agentic behavior).
Portfolio narrative: "production-style RAG pipeline (ingest → chunk → embed → retrieve → ground)
with a hybrid local/paid model strategy and a custom MCP server as the document source."

## Tech Stack
- Java 21, Spring Boot 3.5.16, Gradle (Groovy)
- Spring AI 1.1.8 — pgvector VectorStore, MCP client (Streamable HTTP), Ollama + OpenAI starters, QuestionAnswerAdvisor
- PostgreSQL + pgvector (Docker, port 5433), Lombok, Actuator (health check)
- No JPA: vector store uses JdbcTemplate internally; use `JdbcClient` if metadata queries are needed

## Commands
```bash
docker compose up -d          # pgvector (port 5433)
ollama pull nomic-embed-text  # embedding model (required, all profiles)
ollama pull qwen2.5:7b        # local chat model
./gradlew bootRun             # run with local profile (default)
SPRING_PROFILES_ACTIVE=openai OPENAI_API_KEY=... ./gradlew bootRun  # demo profile
./gradlew test
```

## Architecture
```
User ──HTTP──▶ docmind-rag (:8081)
                ├─ ChatClient
                │    ├─ QuestionAnswerAdvisor ──▶ pgvector (:5433, docmind_rag)
                │    ├─ chat model: Ollama qwen2.5:7b (local) | OpenAI gpt-4o-mini (openai)
                │    └─ MCP tools (searchDocuments/getDocument, agentic fallback)
                │                     │ Streamable HTTP
                └─ Ingestion pipeline ┴──▶ docmind-mcp-server (:8080) ──▶ PostgreSQL (:5432)
                     listDocuments/getDocument → chunk → embed → pgvector
```
`docmind-mcp-server` is the source of truth for raw documents.
`docmind-rag` owns the semantic index (chunks + embeddings) only.

## Hybrid Model Strategy (key design)
| Concern | local profile (default) | openai profile (demo) |
|---------|------------------------|----------------------|
| Chat | Ollama `qwen2.5:7b` | OpenAI `gpt-4o-mini` |
| Embedding | Ollama `nomic-embed-text` (768) | Ollama `nomic-embed-text` (768) |

Embedding is **pinned to Ollama across all profiles**. Why: switching embedding models
changes the vector space (768 vs 1536 dims) and invalidates everything stored in pgvector.
Pinning one embedding model means one vector table, no re-ingestion when switching chat
profiles, and zero embedding cost. Tradeoff: Ollama must be running even for the openai
demo profile — acceptable for a locally-run demo.
Selection is driven by `spring.ai.model.chat` / `spring.ai.model.embedding` properties, not code.

## Project Structure
Base package: `com.docmind.rag`
| Package | Role |
|---------|------|
| `api/` | REST controllers + request/response DTOs |
| `ingestion/` | fetch from MCP server, chunking, embedding, vector store writes |
| `chat/` | ChatClient setup, advisors, prompt templates |
| `config/` | ChatClient bean, MCP tool registration, vector store config |

## API Specs (v1)
Errors: return structured JSON `{error: message}` with proper status; never leak stack traces.

### `POST /api/ingest`
- Body: `{"documentId": 3}` (optional — omit to ingest ALL documents from the MCP server)
- Flow: getDocument/listDocuments via MCP → split (TokenTextSplitter) → embed → store
- Dedup: before storing, delete existing chunks with matching `docId` metadata (idempotent re-ingest)
- Chunk metadata: `docId`, `title`, `tags`
- Returns: `{"ingested": 3, "chunks": 47}`

### `POST /api/chat`
- Body: `{"question": "...", "topK": 5}` (`topK` optional, default 5, max 10)
- Flow: QuestionAnswerAdvisor retrieves topK chunks → grounded answer
- Returns: `{"answer": "...", "sources": [{"docId": 3, "title": "..."}]}` — sources
  deduplicated from retrieved chunk metadata
- Answer language: match the question language (Korean question → Korean answer)
- No relevant context found → answer honestly that the knowledge base has no info; do NOT hallucinate

### Documents REST API (for docmind-web)
Thin REST layer over the existing `McpDocumentClient` so the frontend can browse/create
documents without touching MCP directly. No new business logic — delegate to MCP tools.
- `GET /api/documents?tag=&page=&size=` → `{documents: [{id, title, tags}], total}`
  (maps to `listDocuments`; page default 0, size default 20 max 50)
- `GET /api/documents/{id}` → `{id, title, content, tags}` (maps to `getDocument`;
  not-found → 502 with `{error}` per existing MCP error mapping, or 404 if distinguishable
  from `McpToolMessageException` message — prefer 404 for "No document found")
- `POST /api/documents` `{title, content, tags?}` → 201 Created `{id, title}` (requires a new
  `saveDocument` call in `McpDocumentClient`; validate title ≤ 200, content non-blank → 400)
- Error shape follows the global `{error: message}` convention

## Key Decisions
| Decision | Choice | Why |
|----------|--------|-----|
| Port | 8081 | 8080 is docmind-mcp-server |
| Vector DB | pgvector via `pgvector/pgvector:pg17`, host port 5433, db `docmind_rag` | separate container/volume from mcp-server's postgres (5432); avoids extension/port conflicts |
| Embedding | Ollama nomic-embed-text, 768 dims, pinned across profiles | see Hybrid Model Strategy |
| Chunking | `TokenTextSplitter` (Spring AI default params) | good enough for v1; tune only if retrieval quality demands |
| Retrieval | similarity search, topK 5, threshold ~0.5 | start simple; hybrid/rerank is backlog |
| RAG wiring | `QuestionAnswerAdvisor` on ChatClient | Spring AI idiom, minimal custom code |
| Agentic fallback | MCP tools (search/get) registered on ChatClient | LLM keyword-searches source docs when vector recall misses; the docmind integration story |
| Document source | docmind-mcp-server via MCP client (no direct DB access) | keeps repo boundaries honest — rag never touches mcp-server's DB |
| Secrets | `OPENAI_API_KEY` env var, `.env` gitignored | no keys in repo |
| Config vs secrets | `application-local.yml` IS tracked (contains only the Ollama model choice, no secrets); only `.env` is gitignored | fresh clone boots the local profile without manual file creation |
| Test strategy | test suite is hermetic (pure unit + `@WebMvcTest` slices, no live DB/MCP/Ollama); no `@SpringBootTest` | `./gradlew test` and CI run anywhere; full wiring is verified via `bootRun` + `scripts/demo.sh` |
| MCP client auth | sends `X-API-Key` header via `McpSyncHttpClientRequestCustomizer` (`config/McpAuthConfig`) | docmind-mcp-server protects `/mcp` with the same header — see that repo's PLANNING.md Key Decisions for the auth scheme rationale |

## Out of Scope (v1)
- Reranking, hybrid (keyword+vector) search fusion → backlog
- RAG evaluation (RAGAS etc.) → backlog, strong portfolio add
- Streaming responses (SSE) → backlog
- File upload/PDF parsing (Tika) — mcp-server stores text/markdown only
- UI, multi-tenant

## Verification
- Liveness: `curl http://localhost:8081/actuator/health`
- Automated: hermetic test suite (`./gradlew test`, no external services) + GitHub Actions CI on push/PR
- Full wiring (DB, MCP, Ollama): `bootRun` + `scripts/demo.sh`
- Phase gates: see TASKS.md — a phase is done only when its verify steps pass
- Ingestion: after `POST /api/ingest`, chunk rows exist in pgvector with correct metadata; re-ingest does not duplicate
- Chat: seeded question returns grounded answer citing correct source docs; off-topic question does not hallucinate
- Agentic: log/verify an MCP tool call actually fires for an exact-keyword query
- Both profiles boot: `local` without `OPENAI_API_KEY`, `openai` with it

## Maintenance
When a decision here changes during implementation, update this file in the same session.
