package com.docmind.rag.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * docmind-mcp-server의 listDocuments/getDocument MCP tool을 ChatClient 없이 직접 호출하는 fetch 클라이언트.
 * ingestion 파이프라인 전용 — LLM을 거치지 않는 결정적(deterministic) 호출.
 */
@Component
@RequiredArgsConstructor
public class McpDocumentClient {

	// ponytail: 설정된 MCP 커넥션이 "docmind" 하나뿐이라 getFirst() 사용; 두 번째 서버가 생기면 이름으로 골라야 함
	private final List<McpSyncClient> mcpSyncClients;
	private final ObjectMapper objectMapper;

	@PostConstruct
	void validateMcpClientConfigured() {
		if (mcpSyncClients.isEmpty()) {
			throw new IllegalStateException(
					"No MCP client configured — check spring.ai.mcp.client.*.connections");
		}
	}

	public DocumentPage listDocuments(String tag, Integer page, Integer size) {
		Map<String, Object> arguments = new HashMap<>();
		if (tag != null) {
			arguments.put("tag", tag);
		}
		if (page != null) {
			arguments.put("page", page);
		}
		if (size != null) {
			arguments.put("size", size);
		}
		return callTool("listDocuments", arguments, DocumentPage.class);
	}

	public DocumentDetail getDocument(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		return callTool("getDocument", Map.of("id", id), DocumentDetail.class);
	}

	private <T> T callTool(String toolName, Map<String, Object> arguments, Class<T> resultType) {
		McpSchema.CallToolResult result = mcpSyncClients.getFirst()
				.callTool(new McpSchema.CallToolRequest(toolName, arguments));
		String json = result.content()
				.stream()
				.filter(McpSchema.TextContent.class::isInstance)
				.map(McpSchema.TextContent.class::cast)
				.map(McpSchema.TextContent::text)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("MCP tool '" + toolName + "' returned no text content"));
		String trimmed = json.trim();
		// docmind-mcp-server의 모든 @Tool은 JSON 객체/배열이거나(성공) 평문 메시지(not found, 서버 에러 등)를 반환함
		if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
			throw new McpToolMessageException(toolName, trimmed);
		}
		try {
			return objectMapper.readValue(json, resultType);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("MCP tool '" + toolName + "' returned unparseable result: " + json, e);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DocumentSummary(Long id, String title, String tags) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DocumentDetail(Long id, String title, String content, String tags) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DocumentPage(List<DocumentSummary> documents, long total) {
	}

	/**
	 * MCP tool이 JSON이 아닌 평문 메시지를 반환했을 때 던짐 (예: "No document found with id 3",
	 * "Could not complete '...' due to a server error."). 실제 JSON 파싱 실패(스키마 불일치 등)와는
	 * 구분되는, tool이 의도적으로 알려온 상태임을 나타내는 신호.
	 */
	public static class McpToolMessageException extends RuntimeException {
		public McpToolMessageException(String toolName, String message) {
			super("MCP tool '" + toolName + "' returned: " + message);
		}
	}
}
