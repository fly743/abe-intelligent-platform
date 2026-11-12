package com.abe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;

/**
 * 真实 embedding（阿里云百炼 text-embedding-v4）联调测试。
 *
 * <p>门控：只有设置了环境变量 ABE_RAG_EMBEDDING_API_KEY 才运行，
 * 队友没 key 时该测试自动跳过，不会拖红构建。
 * Spring Boot 的宽松绑定会把 env 变量 ABE_RAG_EMBEDDING_API_KEY
 * 映射到 abe.rag.embedding.api-key，所以代码里不用写 key。
 *
 * <p>集合用独立的 abe_knowledge_embed_test，避免污染正式库。
 */
@EnabledIfEnvironmentVariable(named = "ABE_RAG_EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(
        classes = RealEmbeddingSmokeTest.TestApp.class,
        properties = {
                "abe.rag.embedding.model=text-embedding-v4",
                "abe.rag.embedding.dimensions=1024",
                "abe.rag.qdrant.collection=abe_knowledge_embed_test"
        })
class RealEmbeddingSmokeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "com.abe.rag")
    static class TestApp {
    }

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private QdrantVectorStore vectorStore;

    @Test
    void 真实向量维度为1024() {
        float[] vec = embeddingModel.embed("CP-ABE 的访问策略写在密文中");
        assertEquals(1024, vec.length, "百炼 text-embedding-v4 dimensions 应为 1024");
        System.out.println("维度=" + vec.length);
    }

    @Test
    void 语义检索_不靠字面重合也能命中() {
        // 三条语料，查询与正确目标几乎不共字，只能靠语义相似度
        Document cpabe = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("用户的私钥与其属性集合一一绑定，只有属性满足密文中的访问策略时才能成功解密。")
                .metadata(Map.of("docId", "cp-abe", "title", "ABE 基础", "section", "解密条件"))
                .build();
        Document sandbox = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("Docker 容器默认禁用对外网络，并对内存与 CPU 配额做限制。")
                .metadata(Map.of("docId", "sandbox", "title", "沙箱", "section", "资源限制"))
                .build();
        Document lsss = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("LSSS 是线性秘密共享方案，常用于把访问策略树转换成矩阵形式参与运算。")
                .metadata(Map.of("docId", "lsss", "title", "LSSS", "section", "定义"))
                .build();

        vectorStore.add(List.of(cpabe, sandbox, lsss));

        // 查询词与 cpabe 那条几乎没有共同汉字
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query("什么条件下才能获得解密权限").topK(3).build());

        System.out.println("命中 " + hits.size() + " 条：");
        for (Document hit : hits) {
            System.out.println("  score=" + String.format("%.4f", hit.getScore())
                    + " | " + hit.getMetadata().get("docId")
                    + " | " + hit.getText().substring(0, Math.min(30, hit.getText().length())));
        }

        assertFalse(hits.isEmpty(), "真实 embedding 下不应为空");
        assertEquals("cp-abe", hits.get(0).getMetadata().get("docId"),
                "语义最接近的应是强调‘属性满足策略才能解密’的那条");
    }
}
