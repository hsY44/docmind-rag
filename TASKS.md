# TASKS.md

Working task list. Claude: mark items `[x]` when done, add discovered tasks under the current phase.

## Phase 0 — Setup
- [x] Create Spring Boot project (STS, Spring AI 1.1.8, Java 21)
- [x] Convert `application.properties` → `application.yml` + profiles (local/openai)
- [x] compose.yaml (pgvector:pg17, port 5433) + `.env.example`
- [x] `docker compose up` + `ollama pull nomic-embed-text qwen2.5:7b`
- [x] Verify boot under `local` profile WITHOUT `OPENAI_API_KEY` set
      (if OpenAI autoconfig complains, disable unused openai models via `spring.ai.model.*` properties)
- [x] Verify pgvector schema auto-created (vector_store table, 768 dims)
- [x] Verify MCP client connects to docmind-mcp-server (:8080 must be running)

## Phase 1 — Ingestion Pipeline
- [x] MCP fetch: list/get documents from docmind-mcp-server via MCP client — follow specs in `docs/PLANNING.md`
- [x] Chunking with `TokenTextSplitter`, metadata (docId, title, tags) on each chunk
- [x] Store embeddings in pgvector; dedup by deleting existing chunks with same docId first
- [x] `POST /api/ingest` (single doc + all docs)
- [x] Verify: ingest seed docs → row count > 0, re-ingest → no duplicates

## Phase 2 — RAG Chat
- [x] ChatClient bean + `QuestionAnswerAdvisor` (topK 5)
- [x] `POST /api/chat` returning answer + deduplicated sources — follow specs in `docs/PLANNING.md`
- [ ] Prompt template: answer in question's language, admit when context is missing
- [ ] Verify: seeded question → grounded answer with correct sources; off-topic → no hallucination

## Phase 3 — Agentic (MCP tools)
- [ ] Register docmind MCP tools on ChatClient (search/get only)
- [ ] System prompt: use tools when vector context is insufficient or exact keyword/id lookup is needed
- [ ] Verify: exact-keyword query triggers an MCP tool call (check logs), answer uses tool result

## Phase 4 — Polish
- [ ] Error handling per API spec (structured JSON errors)
- [ ] Smoke test `openai` profile end-to-end (small budget)
- [ ] Demo scenario script (seed via mcp-server → ingest → 3 questions incl. 1 agentic)
- [ ] README.md in Korean (architecture diagram, hybrid model strategy, setup, demo screenshots)

## Backlog (not now)
- RAG evaluation (RAGAS or Spring AI evaluators)
- Hybrid search (keyword + vector fusion), reranking
- Streaming responses (SSE)
- Chat memory (multi-turn)
- Deployment (Docker + cloud)
