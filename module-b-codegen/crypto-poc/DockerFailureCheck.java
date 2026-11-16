package com.abe.codegen.sandbox;

import com.abe.codegen.model.CodegenResult;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 仅用于本地验证，受控源码不会进入对外调用接口。 */
public final class DockerFailureCheck {
    private static CodegenResult source(String java) {
        return new CodegenResult(CodegenResult.Status.SUCCESS, CodegenResult.Stage.GENERATION,
                List.of(new CodegenResult.GeneratedFile("AbeExample.java", java)), "verification");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path module = Path.of("module-b-codegen");
        var executor = new DockerExampleExecutor(module);
        var compile = executor.executeGenerated(source("public class AbeExample { invalid }"), new byte[0]);
        check(compile.outcome() == SandboxResult.Outcome.COMPILATION_FAILED && compile.compilation() == SandboxResult.StageStatus.FAILED && compile.execution() == SandboxResult.StageStatus.NOT_RUN && compile.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "compile: " + compile);
        var run = executor.executeGenerated(source("public class AbeExample { public static void main(String[] a) { throw new RuntimeException(); } }"), new byte[0]);
        check(run.outcome() == SandboxResult.Outcome.EXECUTION_FAILED && run.compilation() == SandboxResult.StageStatus.SUCCESS && run.execution() == SandboxResult.StageStatus.FAILED && run.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "runtime: " + run);
        var shortExecutor = new DockerExampleExecutor(module, TimeUnit.SECONDS.toNanos(15));
        var timeout = shortExecutor.executeGenerated(source("public class AbeExample { public static void main(String[] a) throws Exception { Thread.sleep(120000); } }"), new byte[0]);
        check(timeout.outcome() == SandboxResult.Outcome.TIMEOUT && timeout.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "timeout: " + timeout);
        System.out.println("Docker failure checks passed");
    }
}
