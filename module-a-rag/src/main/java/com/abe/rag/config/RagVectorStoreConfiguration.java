package com.abe.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Qdrant + Embedding 手工装配。
 *
 * <p>为什么不用 starter 自动装配：本项目的 chat 走 DeepSeek（没有 /v1/embeddings 接口），
 * embedding 走另一家供应商，两者 base-url 与 api-key 都不同，
 * 放在同一套 spring.ai.openai.* 前缀下必然冲突。手工装配后互不干扰。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "abe.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagVectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagVectorStoreConfiguration.class);

    @Bean(destroyMethod = "close")
    public QdrantClient abeQdrantClient(RagProperties properties) {
        var q = properties.getQdrant();
        var builder = QdrantGrpcClient.newBuilder(q.getHost(), q.getPort(), q.isUseTls());
        if (StringUtils.hasText(q.getApiKey())) {
            builder.withApiKey(q.getApiKey());
        }
        log.info("Qdrant 客户端: {}:{} (collection={})", q.getHost(), q.getPort(), q.getCollection());
        return new QdrantClient(builder.build());
    }

    @Bean
    public EmbeddingModel abeEmbeddingModel(RagProperties properties) {
        var e = properties.getEmbedding();
        var api = OpenAiApi.builder().baseUrl(e.getBaseUrl()).apiKey(e.getApiKey()).build();
        var options = OpenAiEmbeddingOptions.builder().model(e.getModel());
        if (e.getDimensions() > 0) {
            options.dimensions(e.getDimensions());
        }
        log.info("Embedding: {} @ {} dim={}", e.getModel(), e.getBaseUrl(), e.getDimensions());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options.build());
    }

    @Bean
    public QdrantVectorStore abeVectorStore(QdrantClient qdrantClient,
                                            EmbeddingModel embeddingModel,
                                            RagProperties properties) {
        // 不要手动调 afterPropertiesSet()：QdrantVectorStore 实现了 InitializingBean，
        // Spring 会自动回调，建集合（size 取自 embeddingModel.dimensions()，距离 Cosine）就发生在那里。
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(properties.getQdrant().getCollection())
                .initializeSchema(true)
                .build();
    }
}
