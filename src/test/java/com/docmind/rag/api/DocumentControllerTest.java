package com.docmind.rag.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.docmind.rag.ingestion.McpDocumentClient;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentDetail;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentPage;
import com.docmind.rag.ingestion.McpDocumentClient.DocumentSummary;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;
import com.docmind.rag.ingestion.McpDocumentClient.SavedDocument;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	McpDocumentClient documentClient;

	@Test
	void listReturnsDocumentsAndTotal() throws Exception {
		when(documentClient.listDocuments(eq("spring"), eq(0), eq(5)))
			.thenReturn(new DocumentPage(List.of(new DocumentSummary(1L, "t", "spring,boot")), 1));

		mockMvc.perform(get("/api/documents").param("tag", "spring").param("page", "0").param("size", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documents[0].id").value(1))
			.andExpect(jsonPath("$.documents[0].title").value("t"))
			.andExpect(jsonPath("$.documents[0].tags").value("spring,boot"))
			.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void listWithoutParamsPassesNulls() throws Exception {
		when(documentClient.listDocuments(isNull(), isNull(), isNull())).thenReturn(new DocumentPage(List.of(), 0));

		mockMvc.perform(get("/api/documents"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void getReturnsDocumentDetail() throws Exception {
		when(documentClient.getDocument(3L)).thenReturn(new DocumentDetail(3L, "t", "c", "a,b"));

		mockMvc.perform(get("/api/documents/3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(3))
			.andExpect(jsonPath("$.title").value("t"))
			.andExpect(jsonPath("$.content").value("c"))
			.andExpect(jsonPath("$.tags").value("a,b"));
	}

	@Test
	void getUnknownIdReturns404WithError() throws Exception {
		when(documentClient.getDocument(999L))
			.thenThrow(new McpToolMessageException("getDocument", "No document found with id 999"));

		mockMvc.perform(get("/api/documents/999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void getWithNonNumericIdOrPageReturns400() throws Exception {
		mockMvc.perform(get("/api/documents/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());

		mockMvc.perform(get("/api/documents").param("page", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void getWithOtherMcpMessageReturns502() throws Exception {
		when(documentClient.getDocument(3L))
			.thenThrow(new McpToolMessageException("getDocument", "Could not complete 'getDocument' due to a server error."));

		mockMvc.perform(get("/api/documents/3"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void createReturnsIdAndTitle() throws Exception {
		when(documentClient.saveDocument("t", "c", "a,b")).thenReturn(new SavedDocument(7L, "t"));

		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"c\",\"tags\":\"a,b\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(7))
			.andExpect(jsonPath("$.title").value("t"));
	}

	@Test
	void createWithBlankTitleReturns400() throws Exception {
		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\" \",\"content\":\"c\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());

		verify(documentClient, never()).saveDocument(any(), any(), any());
	}

	@Test
	void createWithExactly200CharTitleSucceeds() throws Exception {
		String title = "a".repeat(200);
		when(documentClient.saveDocument(title, "c", null)).thenReturn(new SavedDocument(9L, title));

		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"" + title + "\",\"content\":\"c\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(9));
	}

	@Test
	void createWithTooLongTitleReturns400() throws Exception {
		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"" + "a".repeat(201) + "\",\"content\":\"c\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void createWithBlankContentReturns400() throws Exception {
		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void createWithMcpPlainTextErrorReturns502() throws Exception {
		when(documentClient.saveDocument("t", "c", null))
			.thenThrow(new McpToolMessageException("saveDocument", "Could not complete 'saveDocument' due to a server error."));

		mockMvc
			.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"c\"}"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").exists());
	}
}
