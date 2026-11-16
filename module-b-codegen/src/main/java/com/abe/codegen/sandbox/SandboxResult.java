package com.abe.codegen.sandbox;

import java.util.Objects;

/** 受限执行的阶段结果；尚未执行的阶段必须保持 NOT_RUN。 */
public record SandboxResult(StageStatus compilation, StageStatus execution, Outcome outcome,
                            CleanupStatus cleanup, String message) {
    public SandboxResult {
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(message, "message");
        if (compilation != StageStatus.SUCCESS && execution != StageStatus.NOT_RUN) {
            throw new IllegalArgumentException("编译未成功时不能执行");
        }
        if (outcome == Outcome.SUCCESS && (compilation != StageStatus.SUCCESS || execution != StageStatus.SUCCESS
                || cleanup == CleanupStatus.FAILED)) {
            throw new IllegalArgumentException("成功结果与阶段状态不一致");
        }
    }

    public enum StageStatus { NOT_RUN, SUCCESS, FAILED, TIMEOUT }
    public enum CleanupStatus { NOT_NEEDED, SUCCESS, FAILED }
    public enum Outcome { SUCCESS, INVALID_INPUT, GENERATION_FAILED, ENVIRONMENT_UNAVAILABLE,
        COMPILATION_FAILED, POLICY_NOT_SATISFIED, EXECUTION_FAILED, TIMEOUT, CLEANUP_FAILED }
}
