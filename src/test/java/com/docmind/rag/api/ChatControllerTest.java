package com.docmind.rag.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.docmind.rag.chat.ChatService;
import com.docmind.rag.chat.ChatService.ChatAnswer;
import com.docmind.rag.chat.ChatService.Source;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ChatService chatService;

	@Test
	void validQuestionReturnsAnswerAndSources() throws Exception {
		when(chatService.ask(eq("Spring Boot가 뭐야?"), eq(3)))
			.thenReturn(new ChatAnswer("Spring Boot는 ...", List.of(new Source(1L, "Getting Started with Spring Boot"))));

		mockMvc
			.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"Spring Boot가 뭐야?\",\"topK\":3}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answer").value("Spring Boot는 ..."))
			.andExpect(jsonPath("$.sources[0].docId").value(1))
			.andExpect(jsonPath("$.sources[0].title").value("Getting Started with Spring Boot"));
	}

	@Test
	void missingTopKPassesNullToService() throws Exception {
		when(chatService.ask(eq("질문"), isNull())).thenReturn(new ChatAnswer("답변", List.of()));

		mockMvc
			.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answer").value("답변"));
	}

	@Test
	void blankQuestionReturns400WithError() throws Exception {
		mockMvc
			.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void missingQuestionReturns400WithError() throws Exception {
		mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}
}
