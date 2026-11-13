package com.abe.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.abe.rag.config.RagVectorStoreConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 真实 Embedding + Qdrant 冒烟测试；需显式启用，避免普通构建产生费用。 */
@EnabledIfSystemProperty(named = "abe.integration.real", matches = "true")
@EnabledIfEnvironmentVariable(named = "ABE_RAG_EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(classes = QdrantSmokeTest.TestApp.class, properties = {
        "abe.rag.embedding.model=text-embedding-v4",
        "abe.rag.embedding.dimensions=1024",
        "abe.rag.qdrant.collection=abe_knowledge_real_smoke_test"
})
class QdrantSmokeTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RagVectorStoreConfiguration.class)
    static class TestApp { }

    @Autowired EmbeddingModel embeddingModel;
    @Autowired QdrantVectorStore vectorStore;

    @Test
    void 建集合_写入_检索命中() {
        assertInstanceOf(OpenAiEmbeddingModel.class, embeddingModel);
        Document doc1 = Document.builder().id(UUID.randomUUID().toString())
                .text("CP-ABE 把访问策略写进密文，解密方属性满足策略时才可解密。")
                .metadata(Map.of("docId", "abe-basics", "title", "ABE 基础", "section", "CP-ABE"))
                .build();
        Document doc2 = Document.builder().id(UUID.randomUUID().toString())
                .text("Docker 沙箱禁用网络并限制内存与 CPU 配额。")
                .metadata(Map.of("docId", "sandbox", "title", "沙箱", "section", "限制"))
                .build();
        vectorStore.add(List.of(doc1, doc2));
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query("访问策略写进密文了吗").topK(2).build());
        assertFalse(hits.isEmpty(), "应至少命中一条，为空说明 Qdrant 链路有问题");
        String top = hits.get(0).getText();
        assertTrue(top != null && top.contains("CP-ABE"), "与查询最接近的应是 CP-ABE 那条");
    }
}

