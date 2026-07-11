package com.docmind.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

class McpAuthConfigTest {

	@Test
	void customizerAddsApiKeyHeaderToRequest() {
		McpSyncHttpClientRequestCustomizer customizer = new McpAuthConfig()
			.apiKeyHttpClientRequestCustomizer("secret-key");
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8080/mcp"));

		customizer.customize(builder, "POST", URI.create("http://localhost:8080/mcp"), null, null);

		HttpRequest request = builder.build();
		assertEquals("secret-key", request.headers().firstValue("X-API-Key").orElse(null));
	}
}
