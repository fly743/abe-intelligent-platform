package com.abe.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Java 业务服务入口：装配各业务模块，对外暴露版本化 HTTP 接口。 */
@SpringBootApplication(scanBasePackages = "com.abe")
public class AbeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AbeApplication.class, args);
    }
}
