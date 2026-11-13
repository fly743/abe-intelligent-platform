package com.abe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.abe.rag.corpus.CorpusIngestService;
import com.abe.rag.corpus.IngestReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import com.abe.rag.config.RagVectorStoreConfiguration;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@EnabledIfSystemProperty(named = "abe.integration.real", matches = "true")
@EnabledIfEnvironmentVariable(named = "ABE_RAG_EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(
        classes = CorpusIngestTest.TestApp.class,
        properties = {
                "abe.rag.embedding.model=text-embedding-v4",
                "abe.rag.embedding.dimensions=1024",
                "abe.rag.embedding.batch-size=10",
                "abe.rag.qdrant.collection=abe_knowledge_real_ingest_test",
                "abe.rag.ingest.corpus-dir=docs",
                "abe.rag.retrieve.score-threshold=0.0"
        })
class CorpusIngestTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RagVectorStoreConfiguration.class, com.abe.rag.corpus.CorpusIngestService.class})
    static class TestApp {
    }

    @Autowired
    private CorpusIngestService ingestService;

    @Autowired
    private QdrantVectorStore vectorStore;

    @Autowired
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @Test
    @DisplayName("入库幂等 + 检索可命中")
    void ingestIsIdempotentAndSearchable() {
        org.junit.jupiter.api.Assertions.assertInstanceOf(OpenAiEmbeddingModel.class, embeddingModel);
        IngestReport first = ingestService.ingest(true);
        System.out.println("第一次: 文件=" + first.fileCount() + " 片段=" + first.chunkCount()
                + " 点数=" + first.pointCount());
        assertTrue(first.fileCount() >= 1, "docs 下至少存在一篇语料");
        assertTrue(first.chunkCount() >= 2);
        assertEquals(first.chunkCount(), first.pointCount(), "首次入库点数应等于片段数");

        IngestReport second = ingestService.ingest(false);
        System.out.println("第二次: 文件=" + second.fileCount() + " 片段=" + second.chunkCount()
                + " 点数=" + second.pointCount());
        assertEquals(first.pointCount(), second.pointCount(), "重复入库不应新增点（幂等）");

        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query("谁能解密 CP-ABE 的密文").topK(3).build());
        hits.forEach(h -> System.out.println("  " + String.format("%.4f", h.getScore())
                + " | " + h.getMetadata().get("docId") + " | " + h.getMetadata().get("section")));
        assertFalse(hits.isEmpty());
    }
}

