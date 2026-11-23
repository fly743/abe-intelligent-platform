import com.abe.codegen.sandbox.*;
import java.nio.file.Path;
import java.util.List;
public class DockerExecutorCheck {
  private static void check(boolean value, String message) {
    if (!value) { System.err.println("[FAIL] " + message); throw new AssertionError(message); }
    System.out.println("[PASS] " + message);
  }
  private static void verifyCase(AbeCodegenService service, String name, String policy,
      List<String> attributes, SandboxResult.Outcome expected) throws Exception {
    var result = service.generateAndVerify(policy, attributes, "demo".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var actual = result.execution();
    var expectedRun = expected == SandboxResult.Outcome.SUCCESS
        ? SandboxResult.StageStatus.SUCCESS : SandboxResult.StageStatus.FAILED;
    boolean passed = result.generated().status() == com.abe.codegen.model.CodegenResult.Status.SUCCESS
        && result.generated().files().stream().anyMatch(file -> file.name().equals("AbeExample.java") && !file.content().isBlank())
        && actual.compilation() == SandboxResult.StageStatus.SUCCESS
        && actual.execution() == expectedRun && actual.outcome() == expected
        && actual.cleanup() == SandboxResult.CleanupStatus.SUCCESS;
    String context = name + "；策略=" + policy + "；属性=" + attributes + "；预期=" + expected;
    if (!passed) {
      check(false, context + "（生成=SUCCESS，源码存在，编译=SUCCESS，运行=" + expectedRun
          + "，清理=SUCCESS）；实际生成=" + result.generated().status() + "；生成说明=" + result.generated().message()
          + "；实际文件=" + result.generated().files().stream().map(file -> file.name()).toList() + "；实际执行=" + actual);
    }
    System.out.println("[PASS] " + context + "；实际=" + actual.outcome());
  }
  public static void main(String[] args) throws Exception {
    var executor = new DockerExampleExecutor(Path.of("module-b-codegen"));
    var invalid = executor.execute("A AND", List.of("A"), new byte[0]);
    check(invalid.outcome() == SandboxResult.Outcome.INVALID_INPUT && invalid.compilation() == SandboxResult.StageStatus.NOT_RUN, "非法策略 A AND，属性=[A]；预期=INVALID_INPUT、编译=NOT_RUN；实际=" + invalid);
    var tooLarge = executor.execute("A", List.of("A"), new byte[1024 * 1024 + 1]);
    check(tooLarge.outcome() == SandboxResult.Outcome.INVALID_INPUT, "输入超过 1 MiB；预期=INVALID_INPUT；实际=" + tooLarge);
    var good = executor.execute("(A AND B) OR (A AND C)", List.of("A", "B"), "示例".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check(good.outcome() == SandboxResult.Outcome.SUCCESS && good.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "authorized: " + good);
    var denied = executor.execute("(A AND B) OR (A AND C)", List.of("A"), new byte[0]);
    check(denied.outcome() == SandboxResult.Outcome.POLICY_NOT_SATISFIED && denied.cleanup() == SandboxResult.CleanupStatus.SUCCESS, "denied: " + denied);
    var missing = new DockerExampleExecutor(Path.of("module-b-codegen", "missing")).execute("A", List.of("A"), new byte[0]);
    check(missing.outcome() == SandboxResult.Outcome.ENVIRONMENT_UNAVAILABLE, "模块依赖缺失；预期=ENVIRONMENT_UNAVAILABLE；实际=" + missing);
    var service = new AbeCodegenService(Path.of("module-b-codegen"));
    verifyCase(service, "AND 授权成功", "A AND B", List.of("A", "B"), SandboxResult.Outcome.SUCCESS);
    verifyCase(service, "AND 授权拒绝", "A AND B", List.of("A"), SandboxResult.Outcome.POLICY_NOT_SATISFIED);
    verifyCase(service, "OR 授权成功", "A OR B", List.of("A"), SandboxResult.Outcome.SUCCESS);
    verifyCase(service, "OR 授权拒绝", "A OR B", List.of("C"), SandboxResult.Outcome.POLICY_NOT_SATISFIED);
    verifyCase(service, "AND 外层组合成功", "A AND (B OR C)", List.of("A", "C"), SandboxResult.Outcome.SUCCESS);
    verifyCase(service, "AND 外层组合拒绝", "A AND (B OR C)", List.of("C"), SandboxResult.Outcome.POLICY_NOT_SATISFIED);
    verifyCase(service, "OR 外层组合成功", "(A AND B) OR C", List.of("C"), SandboxResult.Outcome.SUCCESS);
    verifyCase(service, "OR 外层组合拒绝", "(A AND B) OR C", List.of("A"), SandboxResult.Outcome.POLICY_NOT_SATISFIED);
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
