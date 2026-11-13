package com.abe.rag.retrieve;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.abe.rag.config.RagProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RelationshipQueryExpansionTest {
    @Test
    void expandsBothDirectionsForRelationshipQuestion() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RagRetrievalService service = new RagRetrievalService(store, new RagProperties());
        String question = "密文贴属性、私钥写规则属于哪种方案？";
        service.retrieve(question, 3, null);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        String sent = captor.getValue().getQuery();
        assertTrue(sent.startsWith(question));
        assertTrue(sent.contains("密文关联属性集合 私钥关联访问策略"));
        assertTrue(sent.contains("密文关联访问策略 私钥关联属性集合"));
        assertEquals(3, captor.getValue().getTopK());
    }

    @Test
    void leavesOtherQuestionsAndDisabledExpansionUnchanged() {
        RagProperties properties = new RagProperties();
        RagRetrievalService service = new RagRetrievalService(mock(VectorStore.class), properties);
        String unrelated = "论文的抗合谋安全性是什么？";
        assertEquals(unrelated, service.expandRelationshipQuery(unrelated));
        String relation = "密文策略和私钥属性之间是什么关系？";
        assertNotEquals(relation, service.expandRelationshipQuery(relation));
        properties.getRetrieve().setRelationshipQueryExpansion(false);
        assertEquals(relation, service.expandRelationshipQuery(relation));
        assertTrue(service.retrieve("  ", 3, null).isEmpty());
    }
}
