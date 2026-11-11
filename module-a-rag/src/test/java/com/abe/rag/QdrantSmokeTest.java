package com.abe.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

/**
 * Qdrant 配置闭环验证：建集合 → 写入 → 检索命中。
 *
 * <p>用假 embedding（字符直方图 + L2 归一化）代替真实供应商，
 * 把「向量库配置是否正确」与「embedding 供应商是否可调通」解耦：
 * 今天没有 API key 也能验证 Qdrant 链路，明天换上真模型只改 application.yml。
 *
 * <p>本测试写入独立集合 abe_knowledge_smoke（见 src/test/resources/application.yml），
 * 不污染正式知识库 abe_knowledge。
 */
@SpringBootTest(classes = QdrantSmokeTest.TestApp.class)
class QdrantSmokeTest {

    private static final int DIM = 1024;

    /** 测试专用最小上下文：只扫 com.abe.rag 下的配置类，不加载整个业务应用。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.abe.rag")
    static class TestApp {
    }

    /** 用 @Primary 的假 embedding 覆盖 RagVectorStoreConfiguration 里的真实 OpenAiEmbeddingModel。 */
    @TestConfiguration
    static class FakeEmbedding {
        @Bean
        @Primary
        EmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    /**
     * 显式实现 EmbeddingModel。
     * 注意 EmbeddingModel 有两个抽象方法：call(EmbeddingRequest) 与 embed(Document)，
     * 漏掉后者会编译失败；dimensions() 有默认实现，这里覆盖是为了固定 1024 维。
     */
    static class FakeEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> list = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                list.add(new Embedding(vectorOf(inputs.get(i)), i));
            }
            return new EmbeddingResponse(list);
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document.getText());
        }

        @Override
        public int dimensions() {
            return DIM;
        }
    }

    /** 字符直方图向量：共字越多余弦越接近，足以让冒烟测试有合理排序。 */
    private static float[] vectorOf(String text) {
        float[] v = new float[DIM];
        if (text != null) {
            for (char c : text.toCharArray()) {
                if (!Character.isWhitespace(c)) {
                    v[Math.abs(c) % DIM] += 1f;
                }
            }
        }
        double norm = 0D;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm > 0D) {
            float n = (float) Math.sqrt(norm);
            for (int i = 0; i < DIM; i++) {
                v[i] /= n;
            }
        }
        return v;
    }

    @Autowired
    private QdrantVectorStore vectorStore;

    @Test
    void 建集合_写入_检索命中() {
        Document doc1 = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("CP-ABE 把访问策略写进密文，解密方属性满足策略时才可解密。")
                .metadata(Map.of("docId", "abe-basics", "title", "ABE 基础", "section", "CP-ABE"))
                .build();
        Document doc2 = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("Docker 沙箱禁用网络并限制内存与 CPU 配额。")
                .metadata(Map.of("docId", "sandbox", "title", "沙箱", "section", "限制"))
                .build();

        vectorStore.add(List.of(doc1, doc2));
        System.out.println("写入 2 条完成，维度=" + DIM);

        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query("访问策略写进密文了吗").topK(2).build());

        System.out.println("命中 " + hits.size() + " 条：");
        for (Document hit : hits) {
            System.out.println("  score=" + hit.getScore() + " | " + hit.getText());
        }

        assertFalse(hits.isEmpty(), "应至少命中一条，为空说明 Qdrant 链路有问题");
        String top = hits.get(0).getText();
        assertTrue(top != null && top.contains("CP-ABE"),
                "与查询最接近的应是 CP-ABE 那条，实际第一条为: " + top);
    }
}
