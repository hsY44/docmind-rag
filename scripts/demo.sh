#!/usr/bin/env bash
# 데모 시나리오: mcp-server 시드 → docmind-rag 인제스트 → 질문 3개(에이전틱 1개 포함)
# 전제: docker(pgvector), docmind-mcp-server(:8080), Ollama, docmind-rag(:8081, local 프로필)가
# 이미 기동 중이어야 함 — 기동 방법은 docs/PLANNING.md의 Commands 참고.
set -euo pipefail

RAG_URL="${RAG_URL:-http://localhost:8081}"
MCP_DIR="${MCP_DIR:-../docmind-mcp-server}"

echo "== 1. docmind-mcp-server 시드 데이터 적재 =="
(cd "$MCP_DIR" && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U docmind -d docmind < scripts/seed.sql)

echo
echo "== 2. docmind-rag 인제스트 (전체 문서) =="
curl -sf -X POST "$RAG_URL/api/ingest" -H 'Content-Type: application/json' | jq .

ask() {
	local label="$1" question="$2"
	echo
	echo "== $label =="
	echo "Q: $question"
	curl -sf -X POST "$RAG_URL/api/chat" \
		-H 'Content-Type: application/json' \
		-d "$(jq -n --arg q "$question" '{question: $q}')" | jq .
}

ask "3. 질문 1 (벡터 검색)" "MCP가 뭐야?"
ask "4. 질문 2 (벡터 검색)" "Spring Boot의 장점이 뭐야?"
ask "5. 질문 3 (에이전틱 — 정확한 id 지정, MCP tool 호출 유도)" "문서 id 2번을 정확히 검색해서 원문 내용을 알려줘"
