package com.abe.codegen.sandbox;

import com.abe.codegen.generator.JavaExampleGenerator;
import com.abe.codegen.model.CodegenResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 固定模板的容器执行原型；不接受外部源码或容器参数。 */
public final class DockerExampleExecutor {
    private static final int MAX_INPUT = 1024 * 1024;
    private static final int MAX_OUTPUT = 8192;
    private static final long TIMEOUT_SECONDS = 45;
    private final Path module;
    private final long timeoutNanos;
    public DockerExampleExecutor(Path module) { this(module, TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)); }
    // 包内验证使用较短时限，不允许从外部提交源码或设置容器参数。
    DockerExampleExecutor(Path module, long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("超时时间必须为正数");
        this.module = module.toAbsolutePath().normalize();
        this.timeoutNanos = timeoutNanos;
    }

    public SandboxResult execute(String policy, List<String> attributes, byte[] input) throws InterruptedException {
        return executeGenerated(new JavaExampleGenerator().generate(policy, attributes), input);
    }

    // 仅供同包工作流传入固定生成器的结果，不对外暴露任意源码执行接口。
    SandboxResult executeGenerated(CodegenResult generated, byte[] input) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        if (input == null || input.length > MAX_INPUT) return result(SandboxResult.Outcome.INVALID_INPUT, "明文不能为空引用且不能超过 1 MiB");
        if (generated.status() != CodegenResult.Status.SUCCESS) return result(SandboxResult.Outcome.INVALID_INPUT, generated.message());
        DockerPreflight.Result preflight = DockerPreflight.check();
        if (preflight.status() != DockerPreflight.Status.READY) return result(SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, preflight.message());
        Path classes = module.resolve("target/classes");
        Path vendor = module.resolve(".bsw-local/repository/com/abe/vendor");
        if (!Files.isRegularFile(classes.resolve("com/abe/codegen/crypto/BswCryptoService.class")) ||
                !Files.isRegularFile(vendor.resolve("bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar")) ||
                !Files.isRegularFile(vendor.resolve("jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar")) ||
                !Files.isRegularFile(vendor.resolve("jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar")))
            return result(SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, "模块或本地依赖未编译准备");
        String source = Base64.getEncoder().encodeToString(generated.files().get(0).content().getBytes(StandardCharsets.UTF_8));
        String container = "abe-example-" + UUID.randomUUID();
        String script = "printf %s " + source + " | base64 -d > /tmp/AbeExample.java; "
                + "CP=/app/classes:/app/vendor/bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar:/app/vendor/jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar:/app/vendor/jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar; "
                + "javac -J-Xmx128m -encoding UTF-8 -cp \"$CP\" -d /tmp /tmp/AbeExample.java || exit 10; "
                + "printf 'ABE_COMPILED\\n'; exec java -Xmx256m -cp \"/tmp:$CP\" AbeExample";
        List<String> cmd = List.of("docker", "create", "-i", "--name", container, "--pull", "never", "--network", "none", "--memory", "512m", "--cpus", "1", "--pids-limit", "128", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--user", "65534:65534", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m", "--log-driver", "none",
                "--mount", "type=bind,source=" + classes.resolve("com/abe/codegen/crypto") + ",target=/app/classes/com/abe/codegen/crypto,readonly",
                "--mount", "type=bind,source=" + classes.resolve("com/abe/codegen/policy") + ",target=/app/classes/com/abe/codegen/policy,readonly",
                "--mount", "type=bind,source=" + vendor.resolve("bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar") + ",target=/app/vendor/bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar,readonly",
                "--mount", "type=bind,source=" + vendor.resolve("jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar") + ",target=/app/vendor/jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar,readonly",
                "--mount", "type=bind,source=" + vendor.resolve("jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar") + ",target=/app/vendor/jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar,readonly", DockerPreflight.IMAGE, "sh", "-c", script);
        Process create;
        try { create = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException e) { return result(SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, "无法启动 Docker"); }
        try {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            if (!create.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                create.destroyForcibly();
                boolean removed = cleanup(container);
                return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN,
                        removed ? SandboxResult.Outcome.TIMEOUT : SandboxResult.Outcome.CLEANUP_FAILED,
                        removed ? SandboxResult.CleanupStatus.SUCCESS : SandboxResult.CleanupStatus.FAILED,
                        removed ? "创建容器超时" : "创建超时，容器清理未确认");
            }
            if (create.exitValue() != 0) return result(SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, "Docker 创建容器失败");
        } catch (InterruptedException e) {
            create.destroyForcibly();
            boolean removed = cleanup(container);
            Thread.currentThread().interrupt();
            throw new InterruptedException(removed ? "容器创建被中断，已清理" : "容器创建被中断，清理未确认；请检查 Docker");
        }
        Process p;
        try { p = new ProcessBuilder("docker", "start", "-a", "-i", container).redirectErrorStream(true).start(); }
        catch (IOException e) {
            boolean removed = cleanup(container);
            return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN,
                    removed ? SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE : SandboxResult.Outcome.CLEANUP_FAILED,
                    removed ? SandboxResult.CleanupStatus.SUCCESS : SandboxResult.CleanupStatus.FAILED,
                    removed ? "无法启动 Docker" : "Docker 启动失败且清理未确认");
        }
        var output = new java.io.ByteArrayOutputStream();
        AtomicBoolean writeFailed = new AtomicBoolean();
        Thread reader = new Thread(() -> { try (var in = p.getInputStream()) { byte[] b = new byte[1024]; int n; while ((n = in.read(b)) != -1) { int count = Math.min(n, MAX_OUTPUT - output.size()); if (count > 0) output.write(b, 0, count); } } catch (IOException ignored) { } });
        Thread writer = new Thread(() -> { try (var out = p.getOutputStream()) { out.write(input); } catch (IOException e) { writeFailed.set(true); } });
        reader.setDaemon(true); writer.setDaemon(true); reader.start(); writer.start();
        boolean timedOut = false, interrupted = false, cleaned;
        int exit = -1;
        try { long remaining = Math.max(0L, deadline - System.nanoTime()); if (p.waitFor(remaining, TimeUnit.NANOSECONDS)) exit = p.exitValue(); else timedOut = true; }
        catch (InterruptedException e) { interrupted = true; }
        finally { if (p.isAlive()) p.destroyForcibly(); }
        cleaned = cleanup(container);
        try { reader.join(2000); writer.join(2000); }
        catch (InterruptedException e) { interrupted = true; }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException(cleaned ? "容器执行被中断，已清理" : "容器执行被中断，清理未确认；请检查 Docker");
        }
        if (!cleaned) return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.CLEANUP_FAILED, SandboxResult.CleanupStatus.FAILED, timedOut ? "容器执行超时且清理未确认，请检查 Docker" : "容器清理未确认，请检查 Docker");
        if (reader.isAlive() || writer.isAlive()) return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.EXECUTION_FAILED, SandboxResult.CleanupStatus.SUCCESS, "容器输入输出未正常结束");
        String text = output.toString(StandardCharsets.UTF_8);
        boolean compiled = text.contains("ABE_COMPILED\n");
        if (timedOut) return new SandboxResult(compiled ? SandboxResult.StageStatus.SUCCESS : SandboxResult.StageStatus.TIMEOUT, compiled ? SandboxResult.StageStatus.TIMEOUT : SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.TIMEOUT, SandboxResult.CleanupStatus.SUCCESS, "容器执行超时");
        if (!compiled && exit == 125) return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, SandboxResult.CleanupStatus.SUCCESS, "Docker 未能启动容器");
        if (!compiled && exit == 10) return new SandboxResult(SandboxResult.StageStatus.FAILED, SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.COMPILATION_FAILED, SandboxResult.CleanupStatus.SUCCESS, "生成的示例编译失败");
        if (!compiled) return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN, SandboxResult.Outcome.EXECUTION_FAILED, SandboxResult.CleanupStatus.SUCCESS, "容器未正常进入编译阶段");
        if (exit == 0 && !writeFailed.get() && text.contains("DECRYPTION_SUCCESS")) return new SandboxResult(SandboxResult.StageStatus.SUCCESS, SandboxResult.StageStatus.SUCCESS, SandboxResult.Outcome.SUCCESS, SandboxResult.CleanupStatus.SUCCESS, "授权解密成功");
        if (exit == 3 && text.contains("POLICY_NOT_SATISFIED")) return new SandboxResult(SandboxResult.StageStatus.SUCCESS, SandboxResult.StageStatus.FAILED, SandboxResult.Outcome.POLICY_NOT_SATISFIED, SandboxResult.CleanupStatus.SUCCESS, "用户属性不满足访问策略，请检查属性和策略表达式");
        return new SandboxResult(SandboxResult.StageStatus.SUCCESS, SandboxResult.StageStatus.FAILED, SandboxResult.Outcome.EXECUTION_FAILED, SandboxResult.CleanupStatus.SUCCESS, "示例运行失败，请检查运行环境和依赖；具体原因尚未确定");
    }
    private static SandboxResult result(SandboxResult.Outcome outcome, String message) {
        return new SandboxResult(SandboxResult.StageStatus.NOT_RUN, SandboxResult.StageStatus.NOT_RUN, outcome, SandboxResult.CleanupStatus.NOT_NEEDED, message);
    }
    private static boolean cleanup(String container) {
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", container).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
