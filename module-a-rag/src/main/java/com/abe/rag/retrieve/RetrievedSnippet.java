package com.abe.rag.retrieve;

/** 一条检索结果，字段可直接作为答案的引用来源回显。 */
public record RetrievedSnippet(int ref, String docId, String title, String section,
                               String source, double score, String text) {
}
