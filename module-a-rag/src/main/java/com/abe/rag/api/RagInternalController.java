package com.abe.rag.api;

import com.abe.rag.config.RagProperties;
import com.abe.rag.corpus.CorpusIngestService;
import com.abe.rag.corpus.IngestReport;
import com.abe.rag.qa.AskResult;
import com.abe.rag.qa.RagQaService;
import com.abe.rag.retrieve.RagRetrievalService;
import com.abe.rag.retrieve.RetrievedSnippet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** RAG 内部调试接口：仅 Java 服务内网使用，由 Harness 插件转调，前端不直连。 */
@RestController
@RequestMapping("/internal/rag")
@ConditionalOnProperty(prefix = "abe.rag.dev-endpoints", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RagInternalController {

    private final CorpusIngestService ingestService;
    private final RagRetrievalService retrievalService;
    private final RagQaService qaService;
    private final RagProperties properties;

    public RagInternalController(CorpusIngestService ingestService,
                                 RagRetrievalService retrievalService,
                                 RagQaService qaService,
                                 RagProperties properties) {
        this.ingestService = ingestService;
        this.retrievalService = retrievalService;
        this.qaService = qaService;
        this.properties = properties;
    }


    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collection", properties.getQdrant().getCollection());
        result.put("embeddingModel", properties.getEmbedding().getModel());
        result.put("dimensions", properties.getEmbedding().getDimensions());
        try {
            result.put("points", ingestService.countPoints());
            result.put("status", "UP");
        } catch (RuntimeException e) {
            result.put("status", "DOWN");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/ingest")
    public IngestReport ingest(@RequestParam(defaultValue = "false") boolean recreate) {
        if (!properties.getDevEndpoints().isIngestEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "语料入库接口未启用");
        }
        if (recreate && !properties.getDevEndpoints().isAllowRecreate()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "集合重建未启用");
        }
        return ingestService.ingest(recreate);
    }

    @GetMapping("/search")
    public List<RetrievedSnippet> search(@RequestParam("q") String query,
                                         @RequestParam(required = false) Integer topK,
                                         @RequestParam(required = false) String docId) {
        return retrievalService.retrieve(query, topK, docId);
    }

    @GetMapping("/context")
    public Map<String, Object> context(@RequestParam("q") String query,
                                       @RequestParam(required = false) Integer topK) {
        List<RetrievedSnippet> snippets = retrievalService.retrieve(query, topK, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("snippets", snippets);
        result.put("context", retrievalService.buildContext(snippets));
        return result;
    }

    /** 问答：检索 + 生成，返回带引用编号的答案。 */
    @PostMapping("/ask")
    public AskResult ask(@RequestParam("q") String question,
                         @RequestParam(required = false) Integer topK) {
        return qaService.ask(question, topK);
    }

}
