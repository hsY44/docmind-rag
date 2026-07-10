package com.docmind.rag.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docmind.rag.ingestion.IngestionService;
import com.docmind.rag.ingestion.IngestionService.EmptyDocumentException;
import com.docmind.rag.ingestion.IngestionService.IngestResult;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;

import lombok.RequiredArgsConstructor;

/**
 * 문서 ingest 엔드포인트. documentId가 있으면 단일 문서, 없으면 MCP 서버의 전체 문서를 대상으로 함.
 */
@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestController {

	private final IngestionService ingestionService;

	@PostMapping
	public IngestResult ingest(@RequestBody(required = false) IngestRequest request) {
		if (request != null && request.documentId() != null) {
			return new IngestResult(1, ingestionService.ingestDocument(request.documentId()));
		}
		return ingestionService.ingestAll();
	}

	public record IngestRequest(Long documentId) {
	}

	// 이 엔드포인트에서 발생하는 예외만 최소 매핑 (전역 에러 처리는 Phase 4)
	@ExceptionHandler(EmptyDocumentException.class)
	ResponseEntity<Map<String, String>> onEmptyDocument(EmptyDocumentException e) {
		return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
	}

	// MCP tool이 JSON이 아닌 상태 메시지를 반환(문서 없음, 서버 에러 등) → upstream 게이트웨이 에러로 처리
	@ExceptionHandler(McpToolMessageException.class)
	ResponseEntity<Map<String, String>> onMcpToolMessage(McpToolMessageException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
	}
}
