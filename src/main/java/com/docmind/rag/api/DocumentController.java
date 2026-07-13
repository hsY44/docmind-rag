package com.docmind.rag.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.docmind.rag.ingestion.McpDocumentClient;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentDetail;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentPage;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;
import com.docmind.rag.ingestion.McpDocumentClient.SavedDocument;

import lombok.RequiredArgsConstructor;

/**
 * docmind-web용 문서 조회/생성 REST 레이어. 비즈니스 로직 없이 McpDocumentClient에 위임만 한다.
 * page/size 기본값(0/20)과 max 50 클램핑은 mcp-server가 수행하므로 여기서는 pass-through.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

	private final McpDocumentClient documentClient;

	@GetMapping
	public DocumentPage list(@RequestParam(required = false) String tag,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return documentClient.listDocuments(tag, page, size);
	}

	@GetMapping("/{id}")
	public DocumentDetail get(@PathVariable Long id) {
		try {
			return documentClient.getDocument(id);
		}
		catch (McpToolMessageException e) {
			// ponytail: mcp-server는 not-found를 평문 "No document found with id N"으로만 알려줌 —
			// 메시지 매칭이 유일한 판별 수단 (PLANNING.md 스펙이 명시적으로 허용). 그 외 MCP 오류는 502 유지.
			if (e.getMessage().contains("No document found")) {
				throw new DocumentNotFoundException(id);
			}
			throw e;
		}
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SavedDocument create(@RequestBody CreateDocumentRequest request) {
		if (request.title() == null || request.title().isBlank()) {
			throw new IllegalArgumentException("title must not be blank");
		}
		if (request.title().length() > 200) {
			throw new IllegalArgumentException("title must be 200 characters or fewer");
		}
		if (request.content() == null || request.content().isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		return documentClient.saveDocument(request.title(), request.content(), request.tags());
	}

	public record CreateDocumentRequest(String title, String content, String tags) {
	}

	public static class DocumentNotFoundException extends RuntimeException {
		public DocumentNotFoundException(Long id) {
			super("No document found with id " + id);
		}
	}
}
