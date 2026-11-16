package com.abe.codegen.sandbox;

import com.abe.codegen.generator.JavaExampleGenerator;
import com.abe.codegen.model.CodegenResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 项目内生成与运行验证入口。明文仅用于运行，不写入生成结果。 */
public final class AbeCodegenService {
    private final JavaExampleGenerator generator = new JavaExampleGenerator();
    private final DockerExampleExecutor executor;

    public AbeCodegenService(Path moduleDirectory) {
        executor = new DockerExampleExecutor(Objects.requireNonNull(moduleDirectory, "moduleDirectory"));
    }

    public Result generateAndVerify(String policy, List<String> attributes, byte[] plaintext) throws InterruptedException {
        CodegenResult generated = generator.generate(policy, attributes);
        SandboxResult execution = executor.executeGenerated(generated, plaintext);
        return new Result(generated, execution);
    }

    /** generated.files 包含源码与说明；execution 记录编译、运行和清理状态。 */
    public record Result(CodegenResult generated, SandboxResult execution) {
        public Result { Objects.requireNonNull(generated); Objects.requireNonNull(execution); }
    }
}
