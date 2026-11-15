package com.abe.codegen.model;

import java.util.List;
import java.util.Objects;

/** 内部结果模型；具体生成和执行阶段将在后续实现。 */
public record CodegenResult(Status status, Stage stage, List<GeneratedFile> files, String message) {
    public CodegenResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        files = List.copyOf(files);
        Objects.requireNonNull(message, "message");
    }

    public enum Status { SUCCESS, FAILURE }
    public enum Stage { VALIDATION, GENERATION, COMPILATION, EXECUTION }

    public record GeneratedFile(String name, String content) {
        public GeneratedFile {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(content, "content");
        }
    }
}
