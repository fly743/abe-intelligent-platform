package com.abe.codegen.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 检查本地 Docker 前置条件；不拉取镜像，也不启动生成的代码。 */
public final class DockerPreflight {
    public static final String IMAGE = "eclipse-temurin@sha256:5465815640e10433422217335e15a523a3e57021a1f2ae0fc3849380d237979f";
    private static final long TIMEOUT_SECONDS = 8;
    private static final int MAX_OUTPUT = 1024;
    private DockerPreflight() { }

    public enum Status { READY, DOCKER_UNAVAILABLE, UNSUPPORTED_ENGINE, IMAGE_MISSING }
    public record Result(Status status, String message) { }

    public static Result check() throws InterruptedException {
        CommandResult info = run(List.of("docker", "info", "--format", "{{.OSType}}"));
        if (info == null || info.exitCode != 0 || info.output == null) {
            return new Result(Status.DOCKER_UNAVAILABLE, "Docker 不可用或当前进程无权访问引擎");
        }
        if (!"linux".equals(info.output.trim())) return new Result(Status.UNSUPPORTED_ENGINE, "需要 Docker Linux 引擎");
        CommandResult image = run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE));
        if (image == null) return new Result(Status.DOCKER_UNAVAILABLE, "Docker 检查超时或无法启动");
        if (image.exitCode != 0 || image.output == null || !image.output.trim().startsWith("sha256:")) {
            return new Result(Status.IMAGE_MISSING, "固定 JDK 镜像不可用，或 Docker 引擎无法访问");
        }
        return new Result(Status.READY, "Docker Linux 引擎与固定镜像可用；尚未验证容器隔离");
    }

    private static CommandResult run(List<String> command) throws InterruptedException {
        Process process;
        try { process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException e) { return null; }
        // 持续排空输出，但仅保留有限字节，避免输出阻塞和无界分配。
        var buffer = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (var in = process.getInputStream()) {
                byte[] chunk = new byte[512]; int n;
                while ((n = in.read(chunk)) != -1) {
                    int keep = Math.min(n, MAX_OUTPUT - buffer.size());
                    if (keep > 0) buffer.write(chunk, 0, keep);
                }
            } catch (IOException ignored) { }
        }, "docker-preflight-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null;
            reader.join(1000);
            if (reader.isAlive()) return null;
            return new CommandResult(process.exitValue(), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    private record CommandResult(int exitCode, String output) { }
}
