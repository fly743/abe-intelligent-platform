package com.abe.rag.retrieve;

import com.abe.rag.config.RagProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 检索服务：只做召回与出处，不调用大模型（评测 Recall@5 也走这里）。 */
@Service
public class RagRetrievalService {

    private final VectorStore vectorStore;
    private final RagProperties properties;

    public RagRetrievalService(VectorStore vectorStore, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<RetrievedSnippet> retrieve(String query, Integer topK, String docId) {
        if (!StringUtils.hasText(query)) { return List.of(); }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(expandRelationshipQuery(query))
                .topK(topK != null && topK > 0 ? topK : properties.getRetrieve().getTopK())
                .similarityThreshold(properties.getRetrieve().getScoreThreshold());
        if (StringUtils.hasText(docId)) {
            Filter.Expression expr = new FilterExpressionBuilder().eq("docId", docId).build();
            builder.filterExpression(expr);
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());
        List<RetrievedSnippet> snippets = new ArrayList<>();
        if (docs == null) { return snippets; }
        int ref = 1;
        for (Document d : docs) {
            snippets.add(new RetrievedSnippet(ref++, str(d, "docId"), str(d, "title"),
                    str(d, "section"), str(d, "source"),
                    d.getScore() == null ? 0D : d.getScore(), d.getText(),
                    str(d, "type"), str(d, "sourceUrl")));
        }
        return snippets;
    }

    /** 拼成带编号的上下文，模型只依据编号片段作答，前端可把 [n] 反查成出处。 */
    public String buildContext(List<RetrievedSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (RetrievedSnippet s : snippets) {
            sb.append('[').append(s.ref()).append("] ").append(s.title());
            if (StringUtils.hasText(s.section())) { sb.append(" · ").append(s.section()); }
            sb.append("（来源: ").append(s.source());
            if (StringUtils.hasText(s.sourceUrl())) { sb.append("; 链接: ").append(s.sourceUrl()); }
            sb.append("）\n").append(s.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String str(Document d, String key) {
        Object v = d.getMetadata().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 补充两种相反关系，不直接把 CP 或 KP 当作用户问题的答案。 */
    String expandRelationshipQuery(String query) {
        if (!properties.getRetrieve().isRelationshipQueryExpansion()) { return query; }
        boolean mentionsKey = query.contains("私钥") || query.contains("密钥");
        boolean mentionsPolicy = query.contains("规则") || query.contains("策略") || query.contains("结构");
        if (query.contains("密文") && query.contains("属性") && mentionsKey && mentionsPolicy) {
            return query + " 密文关联属性集合 私钥关联访问策略 密文关联访问策略 私钥关联属性集合 ABE";
        }
        return query;
    }
}
