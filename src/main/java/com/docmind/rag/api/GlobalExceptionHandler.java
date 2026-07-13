package com.docmind.rag.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.docmind.rag.api.DocumentController.DocumentNotFoundException;
import com.docmind.rag.ingestion.IngestionService.EmptyDocumentException;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;

import lombok.extern.slf4j.Slf4j;

/**
 * PLANNING.md API 스펙: 모든 에러는 {"error": message} 구조와 적절한 상태 코드로 반환하고
 * 스택 트레이스는 클라이언트에 노출하지 않는다.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(EmptyDocumentException.class)
	ResponseEntity<Map<String, String>> onEmptyDocument(EmptyDocumentException e) {
		return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(DocumentNotFoundException.class)
	ResponseEntity<Map<String, String>> onDocumentNotFound(DocumentNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
	}

	// MCP tool이 JSON이 아닌 상태 메시지를 반환(문서 없음, 서버 에러 등) → upstream 게이트웨이 에러로 처리
	@ExceptionHandler(McpToolMessageException.class)
	ResponseEntity<Map<String, String>> onMcpToolMessage(McpToolMessageException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, String>> onInvalidRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	// McpDocumentClient가 MCP 커넥션 미설정/응답 JSON 파싱 실패 시 던짐 — McpToolMessageException과 같은
	// upstream MCP 실패 부류라 같은 상태 코드로 처리. 단 이 메시지는 MCP tool의 원본 응답 전체를
	// 그대로 담고 있어(McpDocumentClient 확인됨) e.getMessage()를 그대로 노출하면 안 됨 — 서버 로그에만
	// 남기고 클라이언트에는 고정 메시지만 반환한다.
	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<Map<String, String>> onIllegalState(IllegalStateException e) {
		log.warn("MCP client failure: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "MCP tool call failed"));
	}

	// path variable/query param 타입 변환 실패(예: /api/documents/abc) — 클라이언트 입력 오류인데
	// catch-all이 잡으면 500이 되므로 400으로 명시 처리. 원본 메시지는 내부 타입명을 담고 있어
	// 파라미터명만 노출한다.
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Map<String, String>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest().body(Map.of("error", "invalid parameter: " + e.getName()));
	}

	// 지원하지 않는 HTTP 메서드/Content-Type — 아래 catch-all(Exception.class)이 이 두 타입도 잡아
	// Spring의 원래 405/415 처리를 500으로 덮어써버리므로 먼저 정확한 상태 코드로 명시적으로 처리한다.
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Map<String, String>> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Map<String, String>> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error", e.getMessage()));
	}

	// 요청 바디가 JSON이 아니거나 깨진 경우 — Jackson의 원본 파싱 메시지는 내부 타입/필드명을 담고 있어
	// 그대로 노출하지 않고 고정 메시지로 대체
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, String>> onMalformedBody(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest().body(Map.of("error", "malformed request body"));
	}

	// 예상 못한 예외(모델 제공자/벡터스토어 등)에 대한 안전망 — 서버 로그에만 원본을 남기고
	// 클라이언트에는 고정 메시지만 반환해 스택 트레이스/내부 정보 노출을 막는다
	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, String>> onUnexpectedError(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.internalServerError().body(Map.of("error", "internal server error"));
	}
}
