package com.abe.rag.qa;

import com.abe.rag.retrieve.RetrievedSnippet;
import java.util.List;

/**
 * 一次问答结果。
 *
 * @param refused     是否拒答（检索无片段 / 证据不足）
 * @param citations   答案实际引用的片段（按 ref 升序），供前端渲染引用卡片
 * @param invalidRefs 答案中出现但超出候选范围的编号（引用校验失败项）
 */
public record AskResult(String question, String answer, boolean refused, String reason,
                        List<RetrievedSnippet> citations, int retrievedCount,
                        List<Integer> invalidRefs, long latencyMs) {
}
