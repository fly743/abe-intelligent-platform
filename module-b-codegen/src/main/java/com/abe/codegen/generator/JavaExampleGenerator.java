package com.abe.codegen.generator;

import com.abe.codegen.model.CodegenResult;
import com.abe.codegen.policy.AttributeRules;
import com.abe.codegen.policy.BswPolicyConverter;
import com.abe.codegen.policy.PolicyParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/** 生成项目内运行的 Java 演示代码，不接收或嵌入明文。 */
public final class JavaExampleGenerator {
    public CodegenResult generate(String policy, List<String> attributes) {
        final List<String> checked;
        try {
            var tree = new PolicyParser().parse(policy);
            new BswPolicyConverter().convert(tree);
            checked = AttributeRules.validateAttributes(attributes);
        } catch (IllegalArgumentException e) {
            return new CodegenResult(CodegenResult.Status.FAILURE, CodegenResult.Stage.VALIDATION, List.of(), e.getMessage());
        }
        String encodedPolicy = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
        String encodedAttributes = checked.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
        String source = TEMPLATE.replace("__POLICY_BASE64__", encodedPolicy).replace("__ATTRIBUTES__", encodedAttributes);
        return new CodegenResult(CodegenResult.Status.SUCCESS, CodegenResult.Stage.GENERATION,
                List.of(new CodegenResult.GeneratedFile("AbeExample.java", source),
                        new CodegenResult.GeneratedFile("README.md", INSTRUCTIONS)),
                "源码已生成；尚未编译或运行");
    }

    private static final String TEMPLATE = """
            import com.abe.codegen.crypto.BswCryptoService;
            import com.abe.codegen.policy.PolicyParser;
            import java.nio.charset.StandardCharsets;
            import java.util.Arrays;
            import java.util.Base64;
            import java.util.List;

            /** 从标准输入读取原文字节直到 EOF，最多 1 MiB；不输出原文或密钥。 */
            public final class AbeExample {
                public static void main(String[] args) throws Exception {
                    byte[] plaintext = System.in.readNBytes(1024 * 1024 + 1);
                    if (plaintext.length > 1024 * 1024) {
                        Arrays.fill(plaintext, (byte) 0);
                        System.err.println("INPUT_TOO_LARGE");
                        System.exit(2);
                    }
                    int status = 0;
                    try {
                        String policy = new String(Base64.getDecoder().decode("__POLICY_BASE64__"), StandardCharsets.UTF_8);
                        var service = new BswCryptoService();
                        var key = service.generateKey(List.of(__ATTRIBUTES__));
                        var data = service.encrypt(new PolicyParser().parse(policy), plaintext);
                        try {
                            byte[] recovered = service.decrypt(key, data);
                            try {
                                if (!Arrays.equals(plaintext, recovered)) throw new IllegalStateException("回读内容不一致");
                                System.out.println("DECRYPTION_SUCCESS");
                            } finally { Arrays.fill(recovered, (byte) 0); }
                        } catch (BswCryptoService.PolicyNotSatisfiedException e) {
                            System.err.println("POLICY_NOT_SATISFIED");
                            status = 3;
                        }
                    } finally { Arrays.fill(plaintext, (byte) 0); }
                    if (status != 0) System.exit(status);
                }
            }
            """;

    private static final String INSTRUCTIONS = """
            # Java ABE 示例

            此示例需要 Java 17 和当前项目已编译的 B 模块及其已准备的 BSW/jPBC 工件。
            在项目根目录运行以下 PowerShell 命令；把 <输出目录> 替换为保存本文件和 AbeExample.java 的目录。

            ```powershell
            $sampleDir = '<输出目录>'
            $vendor = 'module-b-codegen/.bsw-local/repository/com/abe/vendor'
            $cp = @('module-b-codegen/target/classes', "$vendor/bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar", "$vendor/jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar", "$vendor/jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar", $sampleDir) -join ';'
            javac -encoding UTF-8 -cp $cp (Join-Path $sampleDir 'AbeExample.java')
            java -cp $cp AbeExample
            ```

            从标准输入读取原文字节直到 EOF；空输入表示空明文，保留换行，最大 1 MiB。
            可通过管道传入非敏感演示内容；交互输入需发送 EOF。程序不输出明文。
            退出码 0 表示解密成功，2 表示输入过大，3 表示属性不满足策略。
            该示例在普通进程运行，并非受限沙箱。请勿输入密钥或口令。
            """;
}
