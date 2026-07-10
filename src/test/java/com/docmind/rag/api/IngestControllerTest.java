package com.docmind.rag.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.docmind.rag.ingestion.IngestionService;
import com.docmind.rag.ingestion.IngestionService.EmptyDocumentException;
import com.docmind.rag.ingestion.IngestionService.IngestResult;
import com.docmind.rag.ingestion.McpDocumentClient.McpToolMessageException;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	IngestionService ingestionService;

	@Test
	void singleDocumentIngestReturnsIngestedOne() throws Exception {
		when(ingestionService.ingestDocument(3L)).thenReturn(5);

		mockMvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":3}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ingested").value(1))
			.andExpect(jsonPath("$.chunks").value(5));
	}

	@Test
	void missingDocumentIdIngestsAllDocuments() throws Exception {
		when(ingestionService.ingestAll()).thenReturn(new IngestResult(2, 7));

		mockMvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ingested").value(2))
			.andExpect(jsonPath("$.chunks").value(7));
	}

	@Test
	void emptyDocumentReturns422WithError() throws Exception {
		when(ingestionService.ingestDocument(9L)).thenThrow(new EmptyDocumentException(9L));

		mockMvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":9}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void mcpToolMessageReturns502WithError() throws Exception {
		when(ingestionService.ingestDocument(99L))
			.thenThrow(new McpToolMessageException("getDocument", "No document found with id 99"));

		mockMvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":99}"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").exists());
	}
}
