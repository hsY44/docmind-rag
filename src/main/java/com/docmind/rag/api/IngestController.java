package com.docmind.rag.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docmind.rag.ingestion.IngestionService;
import com.docmind.rag.ingestion.IngestionService.IngestResult;

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
}
