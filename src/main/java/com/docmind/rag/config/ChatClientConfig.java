package com.docmind.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chat model은 spring.ai.model.chat 프로퍼티로 profile별 자동 선택됨(local=Ollama, openai=OpenAI).
 * QuestionAnswerAdvisor는 요청마다 다른 topK를 지원해야 해서(ChatClient.Builder.defaultAdvisors는
 * per-call로 교체가 아니라 추가되므로 고정 advisor를 여기 두면 중복 검색됨) 여기서 조립하지 않고
 * ChatService가 요청마다 만들어 붙인다.
 */
@Configuration
public class ChatClientConfig {

	@Bean
	ChatClient chatClient(ChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}
}
