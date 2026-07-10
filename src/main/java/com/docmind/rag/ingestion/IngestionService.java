package com.docmind.rag.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import com.docmind.rag.ingestion.McpDocumentClient.DocumentDetail;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentPage;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentSummary;

import lombok.RequiredArgsConstructor;

/**
 * 문서를 청킹해 pgvector에 저장하는 파이프라인. 같은 docId의 기존 청크를 먼저 삭제해 재-ingest가 멱등하도록 함.
 */
@Service
@RequiredArgsConstructor
public class IngestionService {

	private final McpDocumentClient mcpDocumentClient;
	private final VectorStore vectorStore;
	private final TokenTextSplitter textSplitter = new TokenTextSplitter();

	public int ingestDocument(Long documentId) {
		DocumentDetail detail = mcpDocumentClient.getDocument(documentId);

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("docId", detail.id());
		metadata.put("title", detail.title());
		if (detail.tags() != null) {
			metadata.put("tags", detail.tags());
		}
		Document parent = new Document(detail.content(), metadata);
		List<Document> chunks = textSplitter.split(parent);

		// 청킹 결과가 비면(내용이 너무 짧아 TokenTextSplitter가 전부 버림) vector store를 건드리지 않고 실패시킴 —
		// dedup delete를 먼저 하면 기존 청크만 사라지고 아무것도 저장 안 되는 조용한 de-index가 되므로 split을 delete보다 먼저 함.
		if (chunks.isEmpty()) {
			throw new EmptyDocumentException(documentId);
		}

		vectorStore.delete(new FilterExpressionBuilder().eq("docId", detail.id()).build());
		vectorStore.add(chunks);
		return chunks.size();
	}

	/**
	 * MCP 서버의 전체 문서를 페이지네이션으로 순회하며 ingest. 내용이 너무 짧아
	 * {@link EmptyDocumentException}이 나는 문서는 skip하고 계속 진행하며,
	 * ingested 카운트에는 실제 청크가 생성된 문서만 포함한다.
	 */
	public IngestResult ingestAll() {
		int docs = 0;
		int chunks = 0;
		int page = 0;
		long collected = 0;
		long total;
		do {
			// listDocuments size 최대 50 (docmind-mcp-server 계약)
			DocumentPage p = mcpDocumentClient.listDocuments(null, page, 50);
			total = p.total();
			for (DocumentSummary s : p.documents()) {
				collected++;
				try {
					chunks += ingestDocument(s.id());
					docs++;
				}
				catch (EmptyDocumentException e) {
					// 너무 짧은 문서는 skip
				}
			}
			page++;
			if (p.documents().isEmpty()) {
				break; // 안전장치: total 불일치 시 무한루프 방지
			}
		} while (collected < total);
		return new IngestResult(docs, chunks);
	}

	/** ingest 결과 요약. REST 응답 바디로도 그대로 직렬화됨 ({@code {"ingested":N,"chunks":M}}). */
	public record IngestResult(int ingested, int chunks) {
	}

	/**
	 * 문서 내용이 너무 짧아 인덱싱 가능한 청크가 하나도 생성되지 않았을 때 던짐.
	 * 기존 인덱스는 보존되며(삭제 전에 던짐), 향후 POST /api/ingest에서 structured error로 매핑할 수 있음.
	 */
	public static class EmptyDocumentException extends RuntimeException {
		public EmptyDocumentException(Long documentId) {
			super("Document " + documentId + " produced no indexable chunks (content too short or empty)");
		}
	}
}
