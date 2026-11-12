package com.abe.rag.corpus;

/**
 * 切分后的片段，携带完整出处元数据（答案可核查引用的数据基础）。
 *
 * @param docId      文档 ID（相对语料根目录的路径，去扩展名）
 * @param title      文档标题（front matter 的 title，缺省取第一个 # 标题）
 * @param section    片段所属章节（最近的 ## / ### 标题）
 * @param type       文档类型：faq / concept / compare / paper / note（front matter 或目录名推断）
 * @param sourceUrl  来源链接（front matter 的 sourceUrl，可为空）
 * @param source     原始文件相对路径
 * @param chunkIndex 文档内片段序号，从 0 开始
 * @param text       片段正文（含起始章节标题行，便于检索命中）
 */
public record CorpusChunk(String docId, String title, String section, String type,
                          String sourceUrl, String source, int chunkIndex, String text) {
}

