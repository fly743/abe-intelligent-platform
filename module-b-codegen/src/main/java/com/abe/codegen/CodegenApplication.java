package com.abe.codegen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** B 模块独立服务入口。 */
@SpringBootApplication
public class CodegenApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(CodegenApplication.class);
        app.setDefaultProperties(java.util.Map.of("server.address", "127.0.0.1", "server.port", "8081"));
        app.run(args);
    }
}
