package com.abe.rag.corpus;

import java.util.List;

/** 一次入库的结果，用于接口回显与测试断言。 */
public record IngestReport(String collection, String corpusDir, int fileCount, int chunkCount,
                           long pointCount, long elapsedMs, List<String> failures) {
}
