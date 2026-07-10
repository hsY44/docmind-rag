package com.docmind.rag.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * QuestionAnswerAdvisor로 pgvector를 검색해 grounded answer + 검색된 문서의 sources를 반환.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

	private static final int DEFAULT_TOP_K = 5;
	private static final int MAX_TOP_K = 10;

	private final ChatClient chatClient;
	private final VectorStore vectorStore;

	public ChatAnswer ask(String question, Integer topK) {
		QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
			.searchRequest(SearchRequest.builder().topK(resolveTopK(topK)).similarityThreshold(0.5).build())
			.build();
		// chatResponse()는 advisor 체인+LLM 호출을 다시 실행하므로 정확히 한 번만 호출한다.
		ChatResponse response = chatClient.prompt().user(question).advisors(qaAdvisor).call().chatResponse();
		String answer = response.getResult().getOutput().getText();
		List<Document> retrieved = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
		return new ChatAnswer(answer, dedupSources(retrieved));
	}

	int resolveTopK(Integer requested) {
		if (requested == null) {
			return DEFAULT_TOP_K;
		}
		return Math.min(Math.max(requested, 1), MAX_TOP_K);
	}

	List<Source> dedupSources(List<Document> documents) {
		if (documents == null) {
			return List.of();
		}
		Map<Object, String> byDocId = new LinkedHashMap<>();
		for (Document document : documents) {
			byDocId.putIfAbsent(document.getMetadata().get("docId"), (String) document.getMetadata().get("title"));
		}
		return byDocId.entrySet().stream().map(e -> new Source((Long) e.getKey(), e.getValue())).toList();
	}

	public record ChatAnswer(String answer, List<Source> sources) {
	}

	public record Source(Long docId, String title) {
	}
}
