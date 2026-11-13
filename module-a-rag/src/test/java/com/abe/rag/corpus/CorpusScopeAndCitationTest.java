package com.abe.rag.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusScopeAndCitationTest {
    @Test
    void qaIsExcludedByDefaultAndCanBeIncluded(@TempDir Path root) throws Exception {
        Path note = Files.createDirectories(root.resolve("papers")).resolve("note.md");
        Path qa = Files.createDirectories(root.resolve("faq")).resolve("note.qa.md");
        Files.writeString(note, "note");
        Files.writeString(qa, "qa");
        Files.writeString(root.resolve(".hidden.md"), "hidden");
        assertEquals(List.of(note), CorpusIngestService.listFiles(root, false));
        assertEquals(2, CorpusIngestService.listFiles(root, true).size());
        assertTrue(CorpusIngestService.listFiles(root, true).contains(qa));
    }

    @Test
    void publishedPaperChunksCarryLocationAndSourceMetadata() throws Exception {
        Path file = Path.of("docs/papers/bsw2007-cp-abe.md");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<CorpusChunk> chunks = new MarkdownCorpusSplitter(800, 120)
                .split("papers/bsw2007-cp-abe", "papers/bsw2007-cp-abe.md", text);
        assertFalse(chunks.isEmpty());
        for (CorpusChunk chunk : chunks) {
            assertEquals("BSW 2007 CP-ABE 详细知识笔记", chunk.title());
            assertEquals("paper-note", chunk.type());
            assertEquals("https://doi.org/10.1109/SP.2007.11", chunk.sourceUrl());
            assertEquals("papers/bsw2007-cp-abe.md", chunk.source());
            assertTrue(chunk.section().contains("BSW 2007, PDF 第"), chunk.section());
        }
    }

    @Test
    void qaSectionsAreIndividuallyTraceable() throws Exception {
        Path file = Path.of("docs/faq/bsw2007-cp-abe.qa.md");
        List<CorpusChunk> chunks = new MarkdownCorpusSplitter(800, 120)
                .split("faq/bsw2007-cp-abe.qa", "faq/bsw2007-cp-abe.qa.md",
                        Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(chunks.size() >= 27);
        for (CorpusChunk chunk : chunks) {
            assertEquals("qa", chunk.type());
            assertEquals("https://doi.org/10.1109/SP.2007.11", chunk.sourceUrl());
            assertTrue(chunk.section().contains("BSW 2007, PDF 第"), chunk.section());
        }
    }
}
