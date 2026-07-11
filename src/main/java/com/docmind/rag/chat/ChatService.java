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
		SearchRequest searchRequest = SearchRequest.builder()
			.query(question).topK(resolveTopK(topK)).similarityThreshold(0.5).build();
		QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
			.searchRequest(searchRequest)
			.build();
		try {
			// chatResponse()는 advisor 체인 + LLM(+ tool 호출) 전체를 실행하므로 정확히 한 번만 호출한다.
			ChatResponse response = chatClient.prompt().user(question).advisors(qaAdvisor).call().chatResponse();
			List<Document> retrieved = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
			List<Source> sources = mergeSources(dedupSources(retrieved), ToolCallSourceCollector.drain());
			// 벡터 검색도 tool 호출도 근거를 못 찾으면 모델 답변 대신 질문 언어에 맞는 고정 안내로 대체
			// (qwen2.5:7b는 근거 없는 경로에서 답변 언어가 흔들리므로 결정적으로 보정)
			if (sources.isEmpty()) {
				return new ChatAnswer(noContextMessage(question), sources);
			}
			return new ChatAnswer(response.getResult().getOutput().getText(), sources);
		}
		finally {
			// 요청 스레드가 풀에서 재사용될 때 이전 요청의 캡처가 새지 않도록 항상 비운다
			ToolCallSourceCollector.clear();
		}
	}

	// 질문에 한글이 있으면 한국어, 아니면 영어 안내 (프로젝트 범위는 KR/EN)
	String noContextMessage(String question) {
		boolean korean = question.codePoints()
			.anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
		return korean ? "지식 베이스에 관련 정보가 없습니다."
			: "The knowledge base has no relevant information to answer this question.";
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
			Object docId = document.getMetadata().get("docId");
			if (docId == null) {
				continue; // ingestion 밖에서 들어온 docId 없는 청크 방어 — sources에서 제외 (아래 Number 변환 NPE 방지)
			}
			byDocId.putIfAbsent(docId, (String) document.getMetadata().get("title"));
		}
		// pgvector가 JSONB 메타데이터의 docId를 Integer로 역직렬화하므로 Long 캐스팅 대신 Number로 변환.
		return byDocId.entrySet().stream().map(e -> new Source(((Number) e.getKey()).longValue(), e.getValue()))
			.toList();
	}

	// 벡터 검색 sources와 tool 호출로 얻은 sources를 docId 기준으로 합친다(같은 문서면 벡터 쪽 제목 우선).
	List<Source> mergeSources(List<Source> vectorSources, List<Source> toolSources) {
		if (toolSources.isEmpty()) {
			return vectorSources;
		}
		Map<Long, String> byDocId = new LinkedHashMap<>();
		for (Source s : vectorSources) {
			byDocId.putIfAbsent(s.docId(), s.title());
		}
		for (Source s : toolSources) {
			byDocId.putIfAbsent(s.docId(), s.title());
		}
		return byDocId.entrySet().stream().map(e -> new Source(e.getKey(), e.getValue())).toList();
	}

	public record ChatAnswer(String answer, List<Source> sources) {
	}

	public record Source(Long docId, String title) {
	}
}
