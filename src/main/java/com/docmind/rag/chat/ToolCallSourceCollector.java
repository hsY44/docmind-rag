package com.docmind.rag.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.docmind.rag.chat.ChatService.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * chatClient에 등록된 MCP tool(searchDocuments/getDocument)이 요청 처리 중 가져온 문서를
 * sources에 반영하기 위한 요청(스레드)별 수집기. tool 호출은 chatClient.call()과 같은
 * 스레드에서 동기 실행되므로 ThreadLocal로 충분하다.
 */
@Slf4j
public final class ToolCallSourceCollector {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final ThreadLocal<List<Source>> CAPTURED = ThreadLocal.withInitial(ArrayList::new);

	private ToolCallSourceCollector() {
	}

	// MCP tool의 원본 응답(JSON 배열로 감싸인 TextContent)에서 docId/title을 뽑아낸다.
	public static List<Source> parseSources(String rawToolResult) {
		String innerText;
		try {
			// TextContent 봉투 자체가 깨진 경우 — MCP 프로토콜/Spring AI 버전 변경 의심, 로그 남김
			JsonNode content = OBJECT_MAPPER.readTree(rawToolResult);
			innerText = content.get(0).get("text").asText();
		}
		catch (Exception e) {
			log.warn("MCP tool 응답이 예상된 TextContent 포맷이 아님: {}", e.toString());
			return List.of();
		}

		JsonNode payload;
		try {
			payload = OBJECT_MAPPER.readTree(innerText);
		}
		catch (Exception e) {
			// not-found/에러 같은 평문 응답은 JSON 파싱이 실패하므로 빈 목록으로 처리 — tool이 의도적으로
			// 알려온 상태라 로그 없이 빈 목록으로 처리
			return List.of();
		}

		Stream<JsonNode> nodes = payload.isArray()
			? StreamSupport.stream(payload.spliterator(), false)
			: Stream.of(payload);
		return nodes.filter(ToolCallSourceCollector::hasValidIdAndTitle)
			.map(n -> new Source(n.get("id").asLong(), n.get("title").asText()))
			.toList();
	}

	private static boolean hasValidIdAndTitle(JsonNode n) {
		if (!n.hasNonNull("id") || !n.hasNonNull("title")) {
			return false;
		}
		// canConvertToLong()은 6.7 같은 비정수 실수도 range 체크만 통과시켜 asLong()에서 조용히
		// 6으로 잘리게 두므로(Jackson 확인됨), 정수 JSON 숫자인지 확인하는 isIntegralNumber()를 쓴다.
		if (!n.get("id").isIntegralNumber()) {
			log.warn("tool 응답의 id가 정수가 아님, 해당 항목 제외: {}", n.get("id"));
			return false;
		}
		return true;
	}

	public static void record(List<Source> sources) {
		CAPTURED.get().addAll(sources);
	}

	public static List<Source> drain() {
		List<Source> collected = List.copyOf(CAPTURED.get());
		CAPTURED.remove();
		return collected;
	}

	public static void clear() {
		CAPTURED.remove();
	}
}
