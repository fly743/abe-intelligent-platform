package com.abe.rag.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.abe.rag.config.RagProperties;
import com.abe.rag.corpus.CorpusIngestService;
import com.abe.rag.corpus.IngestReport;
import com.abe.rag.qa.RagQaService;
import com.abe.rag.retrieve.RagRetrievalService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.server.ResponseStatusException;

class RagInternalControllerAccessTest {
    private CorpusIngestService ingest;
    private RagRetrievalService retrieval;
    private RagQaService qa;
    private RagProperties properties;
    private RagInternalController controller;

    @BeforeEach
    void setUp() {
        ingest = mock(CorpusIngestService.class);
        retrieval = mock(RagRetrievalService.class);
        qa = mock(RagQaService.class);
        properties = new RagProperties();
        controller = new RagInternalController(ingest, retrieval, qa, properties);
        when(ingest.ingest(anyBoolean())).thenReturn(new IngestReport("test", "docs", 1, 1, 1, 0, List.of()));
    }

    @Test
    void ingestIsForbiddenByDefaultWhileReadsRemainAvailable() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.ingest(false)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.ingest(true)).getStatusCode());
        verify(ingest, never()).ingest(anyBoolean());
        assertEquals("UP", controller.health().get("status"));
        when(retrieval.retrieve("ABE", null, null)).thenReturn(List.of());
        assertTrue(controller.search("ABE", null, null).isEmpty());
    }

    @Test
    void enablingIngestDoesNotEnableRecreate() {
        properties.getDevEndpoints().setIngestEnabled(true);
        assertEquals(1, controller.ingest(false).fileCount());
        verify(ingest).ingest(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.ingest(true)).getStatusCode());
        verify(ingest, never()).ingest(true);
    }

    @Test
    void recreateNeedsBothExplicitSwitches() {
        properties.getDevEndpoints().setAllowRecreate(true);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> controller.ingest(true)).getStatusCode());
        verify(ingest, never()).ingest(anyBoolean());
        properties.getDevEndpoints().setIngestEnabled(true);
        assertEquals(1, controller.ingest(true).fileCount());
        verify(ingest).ingest(true);
    }

    @Test
    void springConfigurationBindsSeparateSwitches() {
        var source = new MapConfigurationPropertySource(Map.of(
                "abe.rag.dev-endpoints.ingest-enabled", "true",
                "abe.rag.dev-endpoints.allow-recreate", "false"));
        RagProperties bound = new Binder(source).bind("abe.rag", Bindable.of(RagProperties.class)).get();
        assertTrue(bound.getDevEndpoints().isIngestEnabled());
        assertFalse(bound.getDevEndpoints().isAllowRecreate());
    }
}
