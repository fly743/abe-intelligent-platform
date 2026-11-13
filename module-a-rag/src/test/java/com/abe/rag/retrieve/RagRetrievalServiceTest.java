package com.abe.rag.retrieve;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.abe.rag.config.RagProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RagRetrievalServiceTest {
    @Test
    void returnsCitationMetadataAndHandlesOlderDocuments() {
        VectorStore store = mock(VectorStore.class);
        Document sourced = Document.builder().text("论文片段")
                .metadata(Map.of("docId", "papers/bsw", "title", "论文", "section", "第 2 节",
                        "source", "papers/bsw.md", "type", "paper-note",
                        "sourceUrl", "https://doi.org/10.1109/SP.2007.11"))
                .build();
        Document legacy = Document.builder().text("旧片段").metadata(Map.of("source", "faq/old.md")).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(sourced, legacy));
        RagRetrievalService service = new RagRetrievalService(store, new RagProperties());

        List<RetrievedSnippet> hits = service.retrieve("什么是 CP-ABE", 2, null);
        assertEquals("paper-note", hits.get(0).type());
        assertEquals("https://doi.org/10.1109/SP.2007.11", hits.get(0).sourceUrl());
        assertEquals("第 2 节", hits.get(0).section());
        assertEquals("", hits.get(1).type());
        assertEquals("", hits.get(1).sourceUrl());
        String context = service.buildContext(hits);
        assertTrue(context.contains("https://doi.org/10.1109/SP.2007.11"));
        assertTrue(context.contains("faq/old.md"));
        assertFalse(context.contains("链接: ）"));
    }
}
