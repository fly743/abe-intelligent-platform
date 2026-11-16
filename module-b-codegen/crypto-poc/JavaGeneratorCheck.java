import com.abe.codegen.generator.JavaExampleGenerator;
import com.abe.codegen.model.CodegenResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** 实际编译并在独立进程运行生成源码；不执行外部不可信源码。 */
public class JavaGeneratorCheck {
    static String cp = System.getProperty("java.class.path");
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void run(Path dir, String policy, List<String> attrs, byte[] input, int expected) throws Exception {
        var result = new JavaExampleGenerator().generate(policy, attrs);
        check(result.status() == CodegenResult.Status.SUCCESS && result.stage() == CodegenResult.Stage.GENERATION, "generate");
        String source = result.files().get(0).content();
        check(!source.contains("Secret中文"), "plaintext in source");
        Path java = dir.resolve("AbeExample.java"); Files.writeString(java, source, StandardCharsets.UTF_8);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-classpath", cp, "-d", dir.toString(), java.toString());
        check(compiled == 0, "compile");
        var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", dir + File.pathSeparator + cp, "AbeExample").redirectErrorStream(true).start();
        try (var out=process.getOutputStream()) { out.write(input); }
        String output=new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == expected, "exit: " + output);
        check(!output.contains("Secret中文"), "plaintext in output");
        check(output.contains(expected == 0 ? "DECRYPTION_SUCCESS" : expected == 3 ? "POLICY_NOT_SATISFIED" : "INPUT_TOO_LARGE"), "status");
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("abe-generated-check-");
        try {
            String p="(A AND B) OR (A AND C)";
            byte[] input="Secret中文\n\"\\u0041\t".getBytes(StandardCharsets.UTF_8);
            run(dir,p,List.of("A","B"),input,0); run(dir,p,List.of("A","C"),input,0);
            run(dir,p,List.of("A"),input,3); run(dir,"profile",List.of("profile"),new byte[0],0);
            run(dir,"A",List.of("A"),new byte[1024*1024],0);
            run(dir,"A",List.of("A"),new byte[1024*1024+1],2);
            var g=new JavaExampleGenerator();
            check(g.generate("A AND",List.of("A")).stage()==CodegenResult.Stage.VALIDATION,"invalid policy");
            check(g.generate("A",List.of("A\";exit" )).status()==CodegenResult.Status.FAILURE,"invalid attribute");
            check(g.generate("A",null).status()==CodegenResult.Status.FAILURE,"null attributes");
            System.out.println("Java generator checks passed");
        } finally {
            try(var files=Files.list(dir)) { for(Path file:files.toList()) Files.deleteIfExists(file); }
            Files.deleteIfExists(dir);
        }
    }
}
