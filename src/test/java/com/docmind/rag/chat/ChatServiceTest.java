package com.docmind.rag.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

// resolveTopK/dedupSources는 순수 함수라 mocking 없이 직접 검증, ask()는 ChatClient 체인을 모킹해 검증.
class ChatServiceTest {

	@Test
	void resolveTopKDefaultsAndClamps() {
		ChatService service = new ChatService(mock(ChatClient.class), mock(VectorStore.class));

		assertEquals(5, service.resolveTopK(null));
		assertEquals(10, service.resolveTopK(20));
		assertEquals(1, service.resolveTopK(0));
		assertEquals(7, service.resolveTopK(7));
	}

	@Test
	void dedupSourcesHandlesNullAndDuplicateDocId() {
		ChatService service = new ChatService(mock(ChatClient.class), mock(VectorStore.class));

		assertEquals(List.of(), service.dedupSources(null));
		assertEquals(List.of(), service.dedupSources(List.of()));

		Document chunk1 = new Document("첫 번째 청크", Map.of("docId", 3L, "title", "제목"));
		Document chunk2 = new Document("같은 문서의 다른 청크", Map.of("docId", 3L, "title", "제목"));
		List<ChatService.Source> sources = service.dedupSources(List.of(chunk1, chunk2));

		assertEquals(List.of(new ChatService.Source(3L, "제목")), sources);
	}

	@Test
	void askReturnsAnswerAndSourcesAndCallsChatResponseOnce() {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
		VectorStore vectorStore = mock(VectorStore.class);

		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.user(anyString())).thenReturn(requestSpec);
		when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(callResponseSpec);

		Document retrieved = new Document("본문", Map.of("docId", 3L, "title", "제목"));
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.keyValue(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(retrieved))
			.build();
		Generation generation = new Generation(new AssistantMessage("답변입니다"));
		ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);
		when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

		ChatService service = new ChatService(chatClient, vectorStore);
		ChatService.ChatAnswer result = service.ask("질문입니다", null);

		assertEquals("답변입니다", result.answer());
		assertEquals(List.of(new ChatService.Source(3L, "제목")), result.sources());
		// chatResponse()를 두 번 부르면 advisor 체인+LLM 호출이 다시 실행되므로 정확히 1번만 호출됐는지 확인
		verify(callResponseSpec, times(1)).chatResponse();
	}
}
