package com.docmind.rag.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

// resolveTopK/dedupSources/mergeSources는 순수 함수라 mocking 없이 직접 검증, ask()는 ChatClient 체인을 모킹해 검증.
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

		// pgvector는 docId를 Integer로 역직렬화하므로 실제와 같게 Integer로 검증(Long 리터럴이면 실환경 버그를 못 잡음)
		Document chunk1 = new Document("첫 번째 청크", Map.of("docId", 3, "title", "제목"));
		Document chunk2 = new Document("같은 문서의 다른 청크", Map.of("docId", 3, "title", "제목"));
		Document orphan = new Document("docId 메타데이터 없는 청크", Map.of("title", "고아 청크"));
		List<ChatService.Source> sources = service.dedupSources(List.of(chunk1, chunk2, orphan));

		// 중복 docId는 하나로, docId 없는 청크는 NPE 없이 제외
		assertEquals(List.of(new ChatService.Source(3L, "제목")), sources);
	}

	@Test
	void mergeSourcesDedupesByDocIdPreferringVectorSource() {
		ChatService service = new ChatService(mock(ChatClient.class), mock(VectorStore.class));

		assertEquals(List.of(), service.mergeSources(List.of(), List.of()));

		List<ChatService.Source> vectorSources = List.of(new ChatService.Source(3L, "벡터 제목"));
		List<ChatService.Source> toolSources = List.of(
			new ChatService.Source(3L, "tool 제목"), // 벡터와 같은 docId — 벡터 쪽 제목이 우선
			new ChatService.Source(7L, "tool 전용 문서"));

		List<ChatService.Source> merged = service.mergeSources(vectorSources, toolSources);

		assertEquals(List.of(new ChatService.Source(3L, "벡터 제목"), new ChatService.Source(7L, "tool 전용 문서")), merged);
	}

	@Test
	void noContextMessageMatchesQuestionLanguage() {
		ChatService service = new ChatService(mock(ChatClient.class), mock(VectorStore.class));

		assertEquals("지식 베이스에 관련 정보가 없습니다.", service.noContextMessage("오늘 서울 날씨 어때?"));
		assertEquals("The knowledge base has no relevant information to answer this question.",
			service.noContextMessage("What is the weather today?"));
	}

	@Test
	void askUsesFixedNoContextMessageWhenNeitherVectorNorToolFindSources() {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.user(anyString())).thenReturn(requestSpec);
		when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(callResponseSpec);

		// RETRIEVED_DOCUMENTS를 아예 채우지 않음 — 벡터 검색이 근거를 못 찾은 상태를 흉내
		ChatResponseMetadata metadata = ChatResponseMetadata.builder().build();
		Generation generation = new Generation(new AssistantMessage("모델이 뭔가 답변을 시도함"));
		when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(generation), metadata));

		ChatService service = new ChatService(chatClient, mock(VectorStore.class));
		ChatService.ChatAnswer result = service.ask("오늘 서울 날씨 어때?", null);

		// LLM은 실제로 호출됐지만(위 스텁), sources가 끝내 비어 있으면 모델 답변 대신 고정 안내를 반환한다
		assertEquals("지식 베이스에 관련 정보가 없습니다.", result.answer());
		assertEquals(List.of(), result.sources());
	}

	@Test
	void askIncludesToolFetchedSourcesEvenWhenVectorRetrievalIsEmpty() {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.user(anyString())).thenReturn(requestSpec);
		when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(callResponseSpec);

		ChatResponseMetadata metadata = ChatResponseMetadata.builder().build(); // 벡터 검색 근거 없음
		Generation generation = new Generation(new AssistantMessage("getDocument로 찾은 내용을 인용한 답변"));
		when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(generation), metadata));

		// 실제로는 chatClient.call() 중 tool 호출 데코레이터가 기록하는 것을, 모킹 경계 밖이라 미리 흉내낸다
		ToolCallSourceCollector.record(List.of(new ChatService.Source(7L, "정확한 문서")));

		ChatService service = new ChatService(chatClient, mock(VectorStore.class));
		ChatService.ChatAnswer result = service.ask("7번 문서 내용 알려줘", null);

		assertEquals("getDocument로 찾은 내용을 인용한 답변", result.answer());
		assertEquals(List.of(new ChatService.Source(7L, "정확한 문서")), result.sources());
	}

	@Test
	void askClearsToolSourcesEvenWhenChatClientThrows() {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.user(anyString())).thenReturn(requestSpec);
		when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
		when(requestSpec.call()).thenThrow(new RuntimeException("모델 호출 실패"));

		// 이전 요청이 실패해서 drain()까지 못 가고 남긴 것처럼, 이 스레드에 미리 캡처를 남겨둔다
		ToolCallSourceCollector.record(List.of(new ChatService.Source(1L, "이전 요청의 흔적")));

		ChatService service = new ChatService(chatClient, mock(VectorStore.class));

		assertThrows(RuntimeException.class, () -> service.ask("질문", null));
		// finally에서 clear()가 실행돼 다음 요청(같은 스레드)으로 캡처가 새지 않아야 한다
		assertTrue(ToolCallSourceCollector.drain().isEmpty());
	}

	@Test
	void askReturnsAnswerAndSourcesAndCallsChatResponseOnce() {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

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

		ChatService service = new ChatService(chatClient, mock(VectorStore.class));
		ChatService.ChatAnswer result = service.ask("질문입니다", null);

		assertEquals("답변입니다", result.answer());
		assertEquals(List.of(new ChatService.Source(3L, "제목")), result.sources());
		// chatResponse()를 두 번 부르면 advisor 체인+LLM 호출이 다시 실행되므로 정확히 1번만 호출됐는지 확인
		verify(callResponseSpec, times(1)).chatResponse();
	}
}
