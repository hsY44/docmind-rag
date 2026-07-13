package com.docmind.rag.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.docmind.rag.ingestion.McpDocumentClient.DocumentDetail;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentPage;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;
import com.docmind.rag.ingestion.McpDocumentClient.SavedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

// MCP tool 응답(JSON text content)을 rag 쪽 DTO로 정확히 파싱하는지 확인하는 순수 단위 테스트.
class McpDocumentClientTest {

	@Test
	void listDocumentsParsesTextContentIntoDocumentPage() {
		McpSyncClient client = mock(McpSyncClient.class);
		String json = "{\"documents\":[{\"id\":1,\"title\":\"t\",\"tags\":\"a,b\",\"createdAt\":\"2026-01-01T00:00:00\"}],\"total\":1}";
		when(client.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(json)), false, null, null));
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		DocumentPage page = documentClient.listDocuments("a", 0, 20);

		assertEquals(1, page.total());
		assertEquals("t", page.documents().get(0).title());
	}

	@Test
	void getDocumentSendsIdArgumentAndParsesDetail() {
		McpSyncClient client = mock(McpSyncClient.class);
		String json = "{\"id\":3,\"title\":\"t\",\"content\":\"c\",\"tags\":null,\"createdAt\":\"2026-01-01T00:00:00\"}";
		when(client.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(json)), false, null, null));
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		DocumentDetail detail = documentClient.getDocument(3L);

		assertEquals("c", detail.content());
		ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
		verify(client).callTool(captor.capture());
		assertEquals("getDocument", captor.getValue().name());
		assertEquals(Map.of("id", 3L), captor.getValue().arguments());
	}

	@Test
	void saveDocumentSendsArgumentsAndParsesSavedDocument() {
		McpSyncClient client = mock(McpSyncClient.class);
		String json = "{\"id\":7,\"title\":\"t\"}";
		when(client.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(json)), false, null, null));
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		SavedDocument saved = documentClient.saveDocument("t", "c", "a,b");

		assertEquals(7L, saved.id());
		ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
		verify(client).callTool(captor.capture());
		assertEquals("saveDocument", captor.getValue().name());
		assertEquals(Map.of("title", "t", "content", "c", "tags", "a,b"), captor.getValue().arguments());
	}

	@Test
	void saveDocumentOmitsNullTagsArgument() {
		McpSyncClient client = mock(McpSyncClient.class);
		when(client.callTool(any()))
			.thenReturn(new CallToolResult(List.of(new TextContent("{\"id\":8,\"title\":\"t\"}")), false, null, null));
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		documentClient.saveDocument("t", "c", null);

		ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
		verify(client).callTool(captor.capture());
		assertEquals(Map.of("title", "t", "content", "c"), captor.getValue().arguments());
	}

	@Test
	void plainTextToolResponseThrowsMcpToolMessageExceptionNotParseError() {
		McpSyncClient client = mock(McpSyncClient.class);
		String message = "No document found with id 999";
		when(client.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(message)), false, null, null));
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		McpToolMessageException e = assertThrows(McpToolMessageException.class, () -> documentClient.getDocument(999L));
		assertTrue(e.getMessage().contains(message));
	}

	@Test
	void getDocumentRejectsNullIdBeforeCallingMcp() {
		McpSyncClient client = mock(McpSyncClient.class);
		McpDocumentClient documentClient = new McpDocumentClient(List.of(client), new ObjectMapper());

		assertThrows(IllegalArgumentException.class, () -> documentClient.getDocument(null));
	}

	@Test
	void emptyMcpClientListFailsFastAtPostConstruct() {
		McpDocumentClient documentClient = new McpDocumentClient(List.of(), new ObjectMapper());

		assertThrows(IllegalStateException.class, documentClient::validateMcpClientConfigured);
	}
}
