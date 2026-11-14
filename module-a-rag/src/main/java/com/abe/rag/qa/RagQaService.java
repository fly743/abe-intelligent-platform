package com.abe.rag.qa;

import com.abe.rag.config.RagProperties;
import com.abe.rag.retrieve.RagRetrievalService;
import com.abe.rag.retrieve.RetrievedSnippet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RAG 问答：检索 → 拼上下文 → 调模型 → 引用校验。
 *
 * <p>三条硬约束：只能依据编号片段作答；证据不足拒答；答案中的 [n] 必须能反查真实片段。
 */
@Service
public class RagQaService {

    private static final Logger log = LoggerFactory.getLogger(RagQaService.class);

    private static final String SYSTEM_PROMPT = """
            你是属性基加密（ABE）领域的严谨技术助手。回答必须遵守：
            1. 只能依据用户消息中「参考片段」的内容作答，不得使用片段之外的知识；
            2. 每个事实性陈述后面用 [编号] 标注来源，编号必须来自给定的片段；
            3. 若片段不足以回答问题，直接输出"根据现有资料无法确定"，并说明缺少哪类信息；
            4. 使用简体中文，先给结论再给 2~5 条要点，不要大段复述片段原文。
            """;

    private static final String REFUSAL = "根据现有资料无法确定。";

    private static final Pattern REF_PATTERN = Pattern.compile("\\[(\\d{1,2})]");

    private final RagRetrievalService retrievalService;
    private final ChatClient chatClient;
    private final RagProperties properties;

    public RagQaService(RagRetrievalService retrievalService, ChatClient chatClient,
                        RagProperties properties) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    public AskResult ask(String question, Integer topK) {
        long start = System.currentTimeMillis();
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }

        List<RetrievedSnippet> snippets = retrievalService.retrieve(question, topK, null);
        if (snippets.isEmpty()) {
            return new AskResult(question, REFUSAL, true,
                    "检索无相关片段（低于阈值 " + properties.getRetrieve().getScoreThreshold() + "）",
                    List.of(), 0, List.of(), System.currentTimeMillis() - start);
        }
        if (!StringUtils.hasText(properties.getChat().getApiKey())) {
            throw new IllegalStateException("未配置对话模型 key：请设置环境变量 ABE_RAG_CHAT_API_KEY");
        }

        String context = retrievalService.buildContext(snippets);
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("参考片段：\n" + context + "\n\n用户问题：" + question)
                .call()
                .content();
        if (answer == null || answer.isBlank()) {
            answer = REFUSAL;
        }

        Set<Integer> usedRefs = new LinkedHashSet<>();
        List<Integer> invalidRefs = new ArrayList<>();
        Matcher matcher = REF_PATTERN.matcher(answer);
        while (matcher.find()) {
            int ref = Integer.parseInt(matcher.group(1));
            if (ref >= 1 && ref <= snippets.size()) {
                usedRefs.add(ref);
            } else {
                invalidRefs.add(ref);
            }
        }
        List<RetrievedSnippet> citations = snippets.stream()
                .filter(s -> usedRefs.contains(s.ref()))
                .toList();

        boolean refused = answer.contains(REFUSAL);
        if (!invalidRefs.isEmpty()) {
            log.warn("无效引用编号 {}，question={}", invalidRefs, question);
        }
        log.info("问答：召回 {} 条，引用 {} 条，拒答={}，耗时 {} ms",
                snippets.size(), citations.size(), refused, System.currentTimeMillis() - start);

        return new AskResult(question, answer.trim(), refused, null, citations,
                snippets.size(), invalidRefs, System.currentTimeMillis() - start);
    }
}
