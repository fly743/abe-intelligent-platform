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
    private static void check(boolean value, String message) {
        if (!value) { System.err.println("[FAIL] " + message); throw new AssertionError(message); }
        System.out.println("[PASS] " + message);
    }
    public static void main(String[] args) throws Exception {
        Path module = Path.of("module-b-codegen");
        var executor = new DockerExampleExecutor(module);
        var compile = executor.executeGenerated(source("public class AbeExample { invalid }"), new byte[0]);
        check(compile.outcome() == SandboxResult.Outcome.COMPILATION_FAILED && compile.compilation() == SandboxResult.StageStatus.FAILED && compile.execution() == SandboxResult.StageStatus.NOT_RUN && compile.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "编译错误；预期=COMPILATION_FAILED、编译=FAILED、运行=NOT_RUN、清理=SUCCESS；实际=" + compile);
        var run = executor.executeGenerated(source("public class AbeExample { public static void main(String[] a) { throw new RuntimeException(); } }"), new byte[0]);
        check(run.outcome() == SandboxResult.Outcome.EXECUTION_FAILED && run.compilation() == SandboxResult.StageStatus.SUCCESS && run.execution() == SandboxResult.StageStatus.FAILED && run.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "运行异常；预期=EXECUTION_FAILED、编译=SUCCESS、运行=FAILED、清理=SUCCESS；实际=" + run);
        var exitOnly = executor.executeGenerated(source("public class AbeExample { public static void main(String[] a) { System.exit(3); } }"), new byte[0]);
        check(exitOnly.outcome() == SandboxResult.Outcome.EXECUTION_FAILED && exitOnly.compilation() == SandboxResult.StageStatus.SUCCESS && exitOnly.execution() == SandboxResult.StageStatus.FAILED && exitOnly.cleanup() == SandboxResult.CleanupStatus.SUCCESS,
                "退出码 3 无拒绝标记；预期=EXECUTION_FAILED、编译=SUCCESS、运行=FAILED、清理=SUCCESS；实际=" + exitOnly);
        var partialMarker = executor.executeGenerated(source("public class AbeExample { public static void main(String[] a) { System.out.println(\"error: POLICY_NOT_SATISFIED\"); System.exit(3); } }"), new byte[0]);
        check(partialMarker.outcome() == SandboxResult.Outcome.EXECUTION_FAILED && partialMarker.compilation() == SandboxResult.StageStatus.SUCCESS && partialMarker.execution() == SandboxResult.StageStatus.FAILED && partialMarker.cleanup() == SandboxResult.CleanupStatus.SUCCESS,
                "退出码 3 但拒绝标记非完整行；预期=EXECUTION_FAILED、编译=SUCCESS、运行=FAILED、清理=SUCCESS；实际=" + partialMarker);
        var shortExecutor = new DockerExampleExecutor(module, TimeUnit.SECONDS.toNanos(15));
        var timeout = shortExecutor.executeGenerated(source("public class AbeExample { public static void main(String[] a) throws Exception { Thread.sleep(120000); } }"), new byte[0]);
        check(timeout.outcome() == SandboxResult.Outcome.TIMEOUT && timeout.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "执行超时；预期=TIMEOUT、清理=SUCCESS；实际=" + timeout);
        System.out.println("Docker failure checks passed");
    }
}
