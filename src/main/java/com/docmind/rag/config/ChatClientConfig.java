package com.docmind.rag.config;

import java.util.Arrays;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.docmind.rag.chat.ToolCallSourceCollector;
import com.docmind.rag.ingestion.McpDocumentClient;

import lombok.extern.slf4j.Slf4j;

/**
 * chat model은 spring.ai.model.chat 프로퍼티로 profile별 자동 선택됨(local=Ollama, openai=OpenAI).
 * QuestionAnswerAdvisor는 요청마다 다른 topK를 지원해야 해서(ChatClient.Builder.defaultAdvisors는
 * per-call로 교체가 아니라 추가되므로 고정 advisor를 여기 두면 중복 검색됨) 여기서 조립하지 않고
 * ChatService가 요청마다 만들어 붙인다.
 */
@Configuration
@Slf4j
public class ChatClientConfig {

	// 질문 언어로 답변하고, context에 근거가 없으면 지어내지 말고 모른다고 인정하도록 지시.
	// 영어 지시가 qwen2.5:7b/gpt-4o-mini 모두에서 잘 지켜짐 — 답변 자체는 질문 언어로 나온다.
	private static final String SYSTEM_PROMPT = """
			You are a helpful assistant for a document knowledge base.
			Answer in the same language as the user's question.
			Base your answer only on the provided context. If the context does not
			contain the information needed to answer, say clearly that you don't know
			and that the knowledge base has no relevant information — do not make up an answer.
			If the provided context is insufficient, or the question asks for a specific
			document by exact keyword or id, use the searchDocuments/getDocument tools to
			look up the source documents directly before answering.
			""";

	private static final Set<String> AGENTIC_MCP_TOOLS = Set.of("searchDocuments", McpDocumentClient.TOOL_GET_DOCUMENT);

	// Spring AI의 SyncMcpToolCallbackProvider 자동설정이 이 Bean을 주입받아 노출 tool을 필터링함
	// (listDocuments/saveDocument는 ingestion·REST 경로 전용, 에이전트에는 노출하지 않음)
	@Bean
	McpToolFilter agenticToolFilter() {
		return (connectionInfo, tool) -> AGENTIC_MCP_TOOLS.contains(tool.name());
	}

	@Bean
	ChatClient chatClient(ChatModel chatModel, ToolCallbackProvider mcpToolCallbackProvider) {
		return ChatClient.builder(chatModel)
			.defaultSystem(SYSTEM_PROMPT)
			.defaultToolCallbacks(sourceCapturingProvider(mcpToolCallbackProvider))
			.build();
	}

	// ToolCallbackProvider.getToolCallbacks()는 요청마다(캐시됨) 호출되므로, 감싸는 wrapper도
	// 그때그때 새로 평가되어 원본의 laziness/캐싱을 그대로 유지한다.
	private static ToolCallbackProvider sourceCapturingProvider(ToolCallbackProvider delegate) {
		return () -> Arrays.stream(delegate.getToolCallbacks())
			.map(ChatClientConfig::captureSources)
			.toArray(ToolCallback[]::new);
	}

	// tool 호출 결과에서 docId/title을 뽑아 ToolCallSourceCollector에 기록 — ChatService가
	// 응답을 만들 때 이 기록을 sources에 합친다(벡터 검색으로 못 찾은 근거를 보완).
	private static ToolCallback captureSources(ToolCallback delegate) {
		// getToolDefinition()은 tool의 JSON 스키마를 매번 새로 만드는 무거운 호출이라(Spring AI MCP
		// 콜백 확인됨) 로그에 쓸 이름은 호출마다 다시 구하지 않고 wrapping 시점에 한 번만 구해둔다.
		String toolName = delegate.getToolDefinition().name();
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return delegate.getToolDefinition();
			}

			@Override
			public ToolMetadata getToolMetadata() {
				return delegate.getToolMetadata();
			}

			@Override
			public String call(String toolInput) {
				return call(toolInput, null);
			}

			@Override
			public String call(String toolInput, ToolContext toolContext) {
				log.info("MCP tool called: {} input={}", toolName, toolInput);
				String result = delegate.call(toolInput, toolContext);
				ToolCallSourceCollector.record(ToolCallSourceCollector.parseSources(result));
				return result;
			}
		};
	}
}
