package com.docmind.rag.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

// MCP tool의 이중으로 감싸인 JSON 응답을 Source로 파싱하는지, ThreadLocal 캡처가 올바르게 도는지 확인하는 순수 단위 테스트.
class ToolCallSourceCollectorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void parseSourcesExtractsSingleDocumentFromGetDocumentShapedResponse() throws Exception {
		// getDocument는 하나의 JSON 객체를 반환 — MCP가 이를 TextContent로 한 번 더 감싼다
		String innerJson = objectMapper.writeValueAsString(
			Map.of("id", 1, "title", "Some Title", "content", "본문", "tags", "a,b"));
		String rawToolResult = objectMapper.writeValueAsString(List.of(Map.of("type", "text", "text", innerJson)));

		List<ChatService.Source> sources = ToolCallSourceCollector.parseSources(rawToolResult);

		assertEquals(List.of(new ChatService.Source(1L, "Some Title")), sources);
	}

	@Test
	void parseSourcesExtractsMultipleDocumentsFromSearchDocumentsShapedResponse() throws Exception {
		// searchDocuments는 JSON 배열을 반환
		String innerJson = objectMapper.writeValueAsString(List.of(
			Map.of("id", 1, "title", "A", "snippet", "..."),
			Map.of("id", 2, "title", "B", "snippet", "...")));
		String rawToolResult = objectMapper.writeValueAsString(List.of(Map.of("type", "text", "text", innerJson)));

		List<ChatService.Source> sources = ToolCallSourceCollector.parseSources(rawToolResult);

		assertEquals(List.of(new ChatService.Source(1L, "A"), new ChatService.Source(2L, "B")), sources);
	}

	@Test
	void parseSourcesReturnsEmptyForNotFoundPlainTextResponse() throws Exception {
		// not-found/에러 응답은 JSON이 아닌 평문 메시지라 안쪽 파싱이 실패한다 — 예외 없이 빈 목록
		String rawToolResult = objectMapper.writeValueAsString(
			List.of(Map.of("type", "text", "text", "No document found with id 999")));

		assertEquals(List.of(), ToolCallSourceCollector.parseSources(rawToolResult));
	}

	@Test
	void parseSourcesFiltersOutNonNumericId() throws Exception {
		// id가 숫자가 아니면 asLong()이 조용히 0을 반환하므로(Jackson 동작), 가짜 출처를 만드는 대신 제외해야 한다
		String innerJson = objectMapper.writeValueAsString(List.of(
			Map.of("id", "abc", "title", "잘못된 id"),
			Map.of("id", 2, "title", "정상 문서")));
		String rawToolResult = objectMapper.writeValueAsString(List.of(Map.of("type", "text", "text", innerJson)));

		List<ChatService.Source> sources = ToolCallSourceCollector.parseSources(rawToolResult);

		assertEquals(List.of(new ChatService.Source(2L, "정상 문서")), sources);
	}

	@Test
	void parseSourcesReturnsEmptyForGarbageInput() {
		assertEquals(List.of(), ToolCallSourceCollector.parseSources("not even json"));
		assertEquals(List.of(), ToolCallSourceCollector.parseSources(""));
	}

	@Test
	void recordAndDrainRoundTripsThenClearsThreadLocal() {
		ToolCallSourceCollector.record(List.of(new ChatService.Source(1L, "A")));
		ToolCallSourceCollector.record(List.of(new ChatService.Source(2L, "B")));

		List<ChatService.Source> drained = ToolCallSourceCollector.drain();

		assertEquals(List.of(new ChatService.Source(1L, "A"), new ChatService.Source(2L, "B")), drained);
		// drain()은 상태를 비우므로 다시 호출하면 빈 목록이어야 한다
		assertTrue(ToolCallSourceCollector.drain().isEmpty());
	}

	@Test
	void clearDiscardsCapturedSourcesWithoutReturningThem() {
		ToolCallSourceCollector.record(List.of(new ChatService.Source(1L, "A")));

		ToolCallSourceCollector.clear();

		assertTrue(ToolCallSourceCollector.drain().isEmpty());
	}
}
