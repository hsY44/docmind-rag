package com.docmind.rag.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

// dedup(delete) -> chunking -> store 순서와 메타데이터 부여를 확인하는 순수 단위 테스트.
class IngestionServiceTest {

	// TokenTextSplitter의 minChunkLengthToEmbed(5자)를 넉넉히 넘기기 위한 샘플 본문 — 너무 짧으면 청크가 전부 버려짐.
	private static final String SAMPLE_CONTENT = "이 문서는 청킹 로직을 검증하기 위한 테스트용 샘플 본문입니다. 최소 길이 조건을 넉넉히 넘기기 위해 문장을 조금 더 길게 작성합니다.";

	@Test
	void ingestDocumentAttachesMetadataAndStoresChunks() {
		McpDocumentClient mcpDocumentClient = mock(McpDocumentClient.class);
		VectorStore vectorStore = mock(VectorStore.class);
		when(mcpDocumentClient.getDocument(3L))
			.thenReturn(new McpDocumentClient.DocumentDetail(3L, "제목", SAMPLE_CONTENT, "a,b"));
		IngestionService service = new IngestionService(mcpDocumentClient, vectorStore);

		int chunkCount = service.ingestDocument(3L);

		ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
		verify(vectorStore).add(captor.capture());
		List<Document> chunks = captor.getValue();
		assertEquals(chunkCount, chunks.size());
		Document chunk = chunks.get(0);
		assertEquals(3L, chunk.getMetadata().get("docId"));
		assertEquals("제목", chunk.getMetadata().get("title"));
		assertEquals("a,b", chunk.getMetadata().get("tags"));
	}

	@Test
	void ingestDocumentDeletesExistingChunksBeforeStoringNewOnes() {
		McpDocumentClient mcpDocumentClient = mock(McpDocumentClient.class);
		VectorStore vectorStore = mock(VectorStore.class);
		when(mcpDocumentClient.getDocument(3L))
			.thenReturn(new McpDocumentClient.DocumentDetail(3L, "제목", SAMPLE_CONTENT, null));
		IngestionService service = new IngestionService(mcpDocumentClient, vectorStore);

		service.ingestDocument(3L);

		InOrder order = inOrder(vectorStore);
		order.verify(vectorStore).delete(new FilterExpressionBuilder().eq("docId", 3L).build());
		order.verify(vectorStore).add(any());
	}

	@Test
	void nullTagsAreOmittedFromChunkMetadataWithoutThrowing() {
		McpDocumentClient mcpDocumentClient = mock(McpDocumentClient.class);
		VectorStore vectorStore = mock(VectorStore.class);
		when(mcpDocumentClient.getDocument(5L))
			.thenReturn(new McpDocumentClient.DocumentDetail(5L, "제목", SAMPLE_CONTENT, null));
		IngestionService service = new IngestionService(mcpDocumentClient, vectorStore);

		assertDoesNotThrow(() -> service.ingestDocument(5L));

		ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
		verify(vectorStore).add(captor.capture());
		assertFalse(captor.getValue().get(0).getMetadata().containsKey("tags"));
	}

	@Test
	void emptyChunksThrowAndLeaveVectorStoreUntouched() {
		McpDocumentClient mcpDocumentClient = mock(McpDocumentClient.class);
		VectorStore vectorStore = mock(VectorStore.class);
		// 5자 이하라 TokenTextSplitter가 청크를 전부 버려 빈 리스트 반환
		when(mcpDocumentClient.getDocument(7L))
			.thenReturn(new McpDocumentClient.DocumentDetail(7L, "제목", "hi", null));
		IngestionService service = new IngestionService(mcpDocumentClient, vectorStore);

		assertThrows(IngestionService.EmptyDocumentException.class, () -> service.ingestDocument(7L));

		verify(vectorStore, never()).delete(any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
		verify(vectorStore, never()).add(any());
	}

	@Test
	void ingestAllPaginatesSkipsEmptyDocsAndSumsCounts() {
		McpDocumentClient mcpDocumentClient = mock(McpDocumentClient.class);
		VectorStore vectorStore = mock(VectorStore.class);
		// total 3인데 페이지당 일부만 반환 → 페이지네이션 루프 검증 (page 0: 2건, page 1: 1건)
		when(mcpDocumentClient.listDocuments(null, 0, 50)).thenReturn(new McpDocumentClient.DocumentPage(
				List.of(new McpDocumentClient.DocumentSummary(1L, "a", null),
						new McpDocumentClient.DocumentSummary(2L, "b", null)),
				3));
		when(mcpDocumentClient.listDocuments(null, 1, 50)).thenReturn(new McpDocumentClient.DocumentPage(
				List.of(new McpDocumentClient.DocumentSummary(3L, "c", null)), 3));
		when(mcpDocumentClient.getDocument(1L))
			.thenReturn(new McpDocumentClient.DocumentDetail(1L, "a", SAMPLE_CONTENT, null));
		// 2번 문서는 너무 짧아 EmptyDocumentException → skip
		when(mcpDocumentClient.getDocument(2L))
			.thenReturn(new McpDocumentClient.DocumentDetail(2L, "b", "hi", null));
		when(mcpDocumentClient.getDocument(3L))
			.thenReturn(new McpDocumentClient.DocumentDetail(3L, "c", SAMPLE_CONTENT, null));
		IngestionService service = new IngestionService(mcpDocumentClient, vectorStore);

		IngestionService.IngestResult result = service.ingestAll();

		assertEquals(2, result.ingested()); // 1, 3만 성공 (2는 skip)
		assertEquals(2, result.chunks()); // SAMPLE_CONTENT는 문서당 청크 1개
		// size=50으로 두 페이지만 조회하고 total 도달 시 종료 (page 2 조회 없음)
		verify(mcpDocumentClient).listDocuments(null, 0, 50);
		verify(mcpDocumentClient).listDocuments(null, 1, 50);
		verify(mcpDocumentClient, never()).listDocuments(eq(null), eq(2), any());
		// 빈 문서(2번)는 vector store 미변경, 성공 문서 2건만 add
		verify(vectorStore, org.mockito.Mockito.times(2)).add(any());
	}
}
