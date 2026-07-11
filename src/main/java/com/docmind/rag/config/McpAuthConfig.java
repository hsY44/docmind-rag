package com.docmind.rag.config;

import java.net.URI;
import java.net.http.HttpRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * docmind-mcp-server가 /mcp를 X-API-Key 헤더로 보호하므로(그 repo의 PLANNING.md Key Decisions
 * 참고), 요청마다 같은 헤더를 붙여 인증한다. 설정된 MCP 커넥션이 하나뿐이라 헤더를 커넥션별로
 * 구분할 필요가 없다.
 */
@Configuration
public class McpAuthConfig {

	@Bean
	McpSyncHttpClientRequestCustomizer apiKeyHttpClientRequestCustomizer(
			@Value("${docmind.mcp.api-key}") String apiKey) {
		return (HttpRequest.Builder builder, String method, URI endpoint, String body,
				McpTransportContext context) -> builder.header("X-API-Key", apiKey);
	}
}
