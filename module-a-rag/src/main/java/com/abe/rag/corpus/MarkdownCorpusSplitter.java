package com.abe.rag.corpus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 语料切分器（纯逻辑，无 Spring 依赖，便于单测）。
 *
 * <p>能力：
 * <ol>
 *   <li>解析 YAML front matter（title / type / source / sourceUrl / updated），从正文中剔除；</li>
 *   <li>按标题切章节，章节内按段落装箱到 maxChars，相邻块保留 overlapChars 重叠；</li>
 *   <li>代码围栏（```）内内容原样保留，不当作标题解析；</li>
 *   <li>跳过 HTML 注释行（&lt;!-- ... --&gt;），便于在语料里留说明而不进向量库。</li>
 * </ol>
 *
 * <p>相比 {@code TokenTextSplitter}，这里额外产出 title / section / type / sourceUrl，
 * 用于生成可核查的引用来源。
 */
public final class MarkdownCorpusSplitter {

    private final int maxChars;
    private final int overlapChars;

    public MarkdownCorpusSplitter(int maxChars, int overlapChars) {
        if (maxChars < 200) {
            throw new IllegalArgumentException("maxChars 过小: " + maxChars);
        }
        this.maxChars = maxChars;
        this.overlapChars = Math.max(0, Math.min(overlapChars, maxChars / 2));
    }

    public List<CorpusChunk> split(String docId, String source, String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> meta = new LinkedHashMap<>();
        String[] lines = splitFrontMatter(text, meta);

        String title = meta.getOrDefault("title", "");
        String type = meta.getOrDefault("type", guessType(source));
        String sourceUrl = meta.getOrDefault("sourceurl", meta.getOrDefault("source-url", ""));

        List<CorpusChunk> chunks = new ArrayList<>();
        String section = "";
        List<String> paragraphs = new ArrayList<>();
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // 代码围栏：整段原样进正文，不做标题解析
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                paragraphs.add(line);
                continue;
            }
            if (inFence) {
                paragraphs.add(line);
                continue;
            }
            // HTML 注释行不进向量库
            if (trimmed.startsWith("<!--")) {
                continue;
            }

            int level = headingLevel(trimmed);
            if (level == 1) {
                // 一级标题只作文档标题，不进正文（避免与 front matter title 重复）
                if (title.isEmpty()) {
                    title = trimmed.substring(level).trim();
                }
                section = "";
                continue;
            }
            if (level > 1) {
                flush(docId, title, section, type, sourceUrl, source, chunks, paragraphs);
                section = trimmed.substring(level).trim();
                // 章节标题行进入正文，提升中文短查询的召回
                paragraphs.add(trimmed);
                continue;
            }
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        flush(docId, title, section, type, sourceUrl, source, chunks, paragraphs);
        return chunks;
    }

    private void flush(String docId, String title, String section, String type, String sourceUrl,
                       String source, List<CorpusChunk> chunks, List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return;
        }
        List<String> pending = new ArrayList<>(paragraphs);
        paragraphs.clear();
        StringBuilder current = new StringBuilder();

        for (String paragraph : pending) {
            for (String piece : hardSplit(paragraph)) {
                boolean overflow = current.length() > 0
                        && current.length() + piece.length() + 1 > maxChars;
                if (overflow) {
                    addChunk(chunks, docId, title, section, type, sourceUrl, source, current.toString());
                    String tail = tail(current.toString());
                    current.setLength(0);
                    if (!tail.isEmpty()) {
                        current.append(tail).append('\n');
                    }
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(piece);
            }
        }
        addChunk(chunks, docId, title, section, type, sourceUrl, source, current.toString());
    }

    private static void addChunk(List<CorpusChunk> chunks, String docId, String title, String section,
                                 String type, String sourceUrl, String source, String body) {
        String content = body == null ? "" : body.trim();
        if (content.isEmpty()) {
            return;
        }
        chunks.add(new CorpusChunk(docId, title, section, type, sourceUrl, source,
                chunks.size(), content));
    }

    /** 单个段落超过 maxChars 时按长度硬切，保证不会产生超长块（超长块会导致 embedding 报错）。 */
    private List<String> hardSplit(String paragraph) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        int step = Math.max(1, maxChars - overlapChars);
        for (int start = 0; start < paragraph.length(); start += step) {
            int end = Math.min(paragraph.length(), start + maxChars);
            pieces.add(paragraph.substring(start, end));
            if (end == paragraph.length()) {
                break;
            }
        }
        return pieces;
    }

    private String tail(String text) {
        if (overlapChars == 0 || text.isEmpty()) {
            return "";
        }
        return text.length() <= overlapChars ? text : text.substring(text.length() - overlapChars);
    }

    /** 解析 --- 包裹的 front matter，返回正文行数组（front matter 内容写入 meta）。 */
    private static String[] splitFrontMatter(String text, Map<String, String> meta) {
        String[] lines = text.split("\n", -1);
        if (lines.length > 1 && lines[0].trim().equals("---")) {
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                for (int i = 1; i < end; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        String key = lines[i].substring(0, colon).trim().toLowerCase();
                        String value = lines[i].substring(colon + 1).trim();
                        if (!key.isEmpty() && !value.isEmpty()) {
                            meta.put(key, value);
                        }
                    }
                }
                return Arrays.copyOfRange(lines, end + 1, lines.length);
            }
        }
        return lines;
    }

    /** 未写 front matter 时按目录名推断文档类型。 */
    private static String guessType(String source) {
        if (source.startsWith("papers")) {
            return "paper";
        }
        if (source.startsWith("notes")) {
            return "note";
        }
        return "faq";
    }

    private static int headingLevel(String line) {
        if (!line.startsWith("#")) {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level > 6 || level == line.length() || line.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }
}
