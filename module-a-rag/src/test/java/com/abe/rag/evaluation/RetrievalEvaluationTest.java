package com.abe.rag.evaluation;

import com.abe.rag.config.RagVectorStoreConfiguration;
import com.abe.rag.retrieve.RagRetrievalService;
import com.abe.rag.retrieve.RetrievedSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnabledIfSystemProperty(named="abe.evaluation.real", matches="true")
@EnabledIfEnvironmentVariable(named="ABE_RAG_EMBEDDING_API_KEY", matches=".+")
@SpringBootTest(classes=RetrievalEvaluationTest.App.class, properties={
    "abe.rag.embedding.model=text-embedding-v4", "abe.rag.embedding.dimensions=1024",
    "abe.rag.qdrant.collection=${ABE_EVAL_COLLECTION:abe_knowledge}", "abe.rag.retrieve.score-threshold=0.15",
    "abe.rag.retrieve.relationship-query-expansion=${ABE_EVAL_EXPANSION:true}"
})
class RetrievalEvaluationTest {
    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({RagVectorStoreConfiguration.class,RagRetrievalService.class}) static class App { }
    record Gold(String doc, String section) { }
    record Question(String id, String question, List<Gold> gold) { }
    record Result(String id, String question, List<Gold> gold, List<RetrievedSnippet> hits) { }
    @Autowired EmbeddingModel model;
    @Autowired RagRetrievalService retrieval;
    @Test void capture() throws Exception {
        assertInstanceOf(OpenAiEmbeddingModel.class, model);
        ObjectMapper json = new ObjectMapper();
        String input = System.getenv().getOrDefault("ABE_EVAL_FILE", "evaluation/retrieval-dev.json");
        Question[] questions = json.readValue(Path.of(input).toFile(),Question[].class);
        List<Result> output = new ArrayList<>();
        for (Question q : questions) {
            List<RetrievedSnippet> hits = retrieval.retrieve(q.question(),10,null);
            output.add(new Result(q.id(),q.question(),q.gold(),hits));
            System.out.println("EVALUATED " + q.id() + " hits=" + hits.size());
        }
        String label = System.getenv().getOrDefault("ABE_EVAL_LABEL", "baseline");
        if (!label.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid evaluation label");
        Path outputPath=Path.of("target/retrieval-" + label + ".json");
        Files.createDirectories(outputPath.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(),output);
    }
}
