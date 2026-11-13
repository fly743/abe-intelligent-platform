package com.abe.rag.corpus;

import com.abe.rag.config.RagProperties;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


/**
 * 语料入库：扫描目录 → 切分 → 批量向量化 → 写入 Qdrant。
 *
 * <p>幂等：point id = UUID(nameUUIDFromBytes(docId#chunkIndex#contentHash))。
 * 内容不变则 id 不变 → 重复入库是覆盖而非追加；内容改了 hash 变 → 旧片段需 recreate 清理。
 * <p>批大小受 embedding 供应商限制（百炼 10 条/请求），用 batch-size 配置控制。
 */
@Service
public class CorpusIngestService {

    private static final Logger log = LoggerFactory.getLogger(CorpusIngestService.class);

    private final QdrantVectorStore vectorStore;
    private final QdrantClient qdrantClient;
    private final RagProperties properties;

    public CorpusIngestService(QdrantVectorStore vectorStore, QdrantClient qdrantClient,
                               RagProperties properties) {
        this.vectorStore = vectorStore;
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    public IngestReport ingest() {
        return ingest(false);
    }

    public IngestReport ingest(boolean recreate) {
        long start = System.currentTimeMillis();
        Path root = Paths.get(properties.getIngest().getCorpusDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("语料目录不存在: " + root + "，请检查 abe.rag.ingest.corpus-dir");
        }
        if (recreate) { recreateCollection(); }

        MarkdownCorpusSplitter splitter = new MarkdownCorpusSplitter(
                properties.getIngest().getChunkMaxChars(),
                properties.getIngest().getChunkOverlapChars());
        int batchSize = Math.max(1, properties.getEmbedding().getBatchSize());
        List<String> failures = new ArrayList<>();
        List<Document> buffer = new ArrayList<>(batchSize);
        int fileCount = 0;
        int chunkCount = 0;

        for (Path file : listFiles(root, properties.getIngest().isIncludeQa())) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String docId = relative.substring(0, relative.lastIndexOf('.'));
            try {
                List<CorpusChunk> chunks = splitter.split(docId, relative,
                        Files.readString(file, StandardCharsets.UTF_8));
                fileCount++;
                chunkCount += chunks.size();
                for (CorpusChunk chunk : chunks) {
                    buffer.add(toDocument(chunk));
                    if (buffer.size() >= batchSize) {
                        vectorStore.add(List.copyOf(buffer));
                        buffer.clear();
                    }
                }
                log.info("已切分 {} -> {} 块", relative, chunks.size());
            } catch (RuntimeException | IOException e) {
                log.warn("处理失败 {}: {}", relative, e.toString());
                failures.add(relative + ": " + e.getMessage());
            }
        }
        if (!buffer.isEmpty()) { vectorStore.add(List.copyOf(buffer)); }

        long elapsed = System.currentTimeMillis() - start;
        long points = countPoints();
        log.info("入库完成: 文件 {} 个, 片段 {} 个, 集合 {} 共 {} 点, 耗时 {} ms",
                fileCount, chunkCount, properties.getQdrant().getCollection(), points, elapsed);
        return new IngestReport(properties.getQdrant().getCollection(), root.toString(),
                fileCount, chunkCount, points, elapsed, failures);
    }

    public long countPoints() {
        try {
            return qdrantClient.countAsync(properties.getQdrant().getCollection()).get();
        } catch (Exception e) {
            throw new IllegalStateException("查询集合点数失败: " + e.getMessage(), e);
        }
    }

    private void recreateCollection() {
        String collection = properties.getQdrant().getCollection();
        try {
            qdrantClient.deleteCollectionAsync(collection).get();
            log.warn("已删除集合 {}", collection);
        } catch (Exception e) {
            log.info("集合 {} 删除跳过: {}", collection, e.getMessage());
        }
        try {
            vectorStore.afterPropertiesSet();   // 按 initializeSchema 重建
        } catch (Exception e) {
            throw new IllegalStateException("重建集合失败: " + e.getMessage(), e);
        }
    }

    /** 片段 → Spring AI Document；id 由 docId + 序号 + 内容哈希决定，保证幂等。 */
    private Document toDocument(CorpusChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("docId", chunk.docId());
        metadata.put("title", chunk.title());
        metadata.put("section", chunk.section());
        metadata.put("type", chunk.type());
        metadata.put("source", chunk.source());
        metadata.put("chunkIndex", chunk.chunkIndex());
        if (StringUtils.hasText(chunk.sourceUrl())) {
            metadata.put("sourceUrl", chunk.sourceUrl());
        }
        return Document.builder()
                .id(pointId(chunk))
                .text(chunk.text())
                .metadata(metadata)
                .build();
    }


    private static String pointId(CorpusChunk chunk) {
        String raw = chunk.docId() + "#" + chunk.chunkIndex() + "#" + sha256(chunk.text());
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> listFiles(Path root, boolean includeQa) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".md") || n.endsWith(".txt");
                    })
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    // QA 文件独立维护，默认跳过，避免与相同知识笔记混合召回。
                    .filter(p -> includeQa || !p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".qa.md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描语料目录失败: " + e.getMessage(), e);
        }
    }
}
