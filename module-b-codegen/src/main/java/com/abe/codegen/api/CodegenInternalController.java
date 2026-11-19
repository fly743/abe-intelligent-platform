package com.abe.codegen.api;

import com.abe.codegen.sandbox.AbeCodegenService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** 本地可信调用方使用；不接受源码或容器参数。 */
@RestController
@RequestMapping("/internal/codegen")
public class CodegenInternalController {
    private final AbeCodegenService service;
    public CodegenInternalController(@Value("${abe.codegen.module-directory:module-b-codegen}") String directory) {
        service = new AbeCodegenService(Path.of(directory));
    }
    public record Request(String policy, List<String> attributes, String plaintext) { }
    @PostMapping("/verify")
    public AbeCodegenService.Result verify(@RequestBody Request request) throws InterruptedException {
        byte[] bytes = request.plaintext() == null ? null : request.plaintext().getBytes(StandardCharsets.UTF_8);
        try { return service.generateAndVerify(request.policy(), request.attributes(), bytes); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
}
