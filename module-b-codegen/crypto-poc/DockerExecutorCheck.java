import com.abe.codegen.sandbox.*;
import java.nio.file.Path;
import java.util.List;
public class DockerExecutorCheck {
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    var executor = new DockerExampleExecutor(Path.of("module-b-codegen"));
    var invalid = executor.execute("A AND", List.of("A"), new byte[0]);
    check(invalid.outcome() == SandboxResult.Outcome.INVALID_INPUT && invalid.compilation() == SandboxResult.StageStatus.NOT_RUN, "invalid input");
    var tooLarge = executor.execute("A", List.of("A"), new byte[1024 * 1024 + 1]);
    check(tooLarge.outcome() == SandboxResult.Outcome.INVALID_INPUT, "input limit");
    var good = executor.execute("(A AND B) OR (A AND C)", List.of("A", "B"), "示例".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check(good.outcome() == SandboxResult.Outcome.SUCCESS && good.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "authorized: " + good);
    var denied = executor.execute("(A AND B) OR (A AND C)", List.of("A"), new byte[0]);
    check(denied.outcome() == SandboxResult.Outcome.POLICY_NOT_SATISFIED && denied.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "denied: " + denied);
    var missing = new DockerExampleExecutor(Path.of("module-b-codegen", "missing")).execute("A", List.of("A"), new byte[0]);
    check(missing.outcome() == SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, "missing dependencies");
    var service = new AbeCodegenService(Path.of("module-b-codegen"));
    var combined = service.generateAndVerify("(A AND B) OR (A AND C)", List.of("A", "C"), "私密示例".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check(combined.generated().status() == com.abe.codegen.model.CodegenResult.Status.SUCCESS, "generated result");
    check(combined.generated().files().size() == 2 && !combined.generated().files().get(0).content().contains("私密示例"), "generated source");
    check(combined.execution().outcome() == SandboxResult.Outcome.SUCCESS && combined.execution().compilation() == SandboxResult.StageStatus.SUCCESS, "combined execution");
    var empty = service.generateAndVerify("(A AND B) OR (A AND C)", List.of("A", "B"), new byte[0]);
    check(empty.execution().outcome() == SandboxResult.Outcome.SUCCESS && empty.execution().execution() == SandboxResult.StageStatus.SUCCESS, "combined empty plaintext");
    var combinedDenied = service.generateAndVerify("(A AND B) OR (A AND C)", List.of("A"), "中文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check(combinedDenied.generated().status() == com.abe.codegen.model.CodegenResult.Status.SUCCESS && combinedDenied.execution().outcome() == SandboxResult.Outcome.POLICY_NOT_SATISFIED && combinedDenied.execution().compilation() == SandboxResult.StageStatus.SUCCESS, "combined policy rejection");
    var standard = service.generateAndVerify("A AND (B OR C)", List.of("A", "B"), "中文\n换行".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check(standard.execution().outcome() == SandboxResult.Outcome.SUCCESS, "ordinary policy and Chinese");
    var invalidCombined = service.generateAndVerify("A AND", List.of("A"), new byte[0]);
    check(invalidCombined.generated().status() == com.abe.codegen.model.CodegenResult.Status.FAILURE && invalidCombined.execution().compilation() == SandboxResult.StageStatus.NOT_RUN, "combined invalid input");
    check(invalidCombined.execution().execution() == SandboxResult.StageStatus.NOT_RUN && invalidCombined.generated().files().isEmpty(), "invalid stages");
    System.out.println("Docker executor checks passed");
  }
}
