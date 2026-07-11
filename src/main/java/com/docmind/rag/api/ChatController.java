package com.docmind.rag.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docmind.rag.chat.ChatService;
import com.docmind.rag.chat.ChatService.ChatAnswer;

import lombok.RequiredArgsConstructor;

/**
 * pgvector 기반 RAG 채팅 엔드포인트.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	@PostMapping
	public ChatAnswer chat(@RequestBody ChatRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			throw new IllegalArgumentException("question must not be blank");
		}
		return chatService.ask(request.question(), request.topK());
	}

	public record ChatRequest(String question, Integer topK) {
	}
}
