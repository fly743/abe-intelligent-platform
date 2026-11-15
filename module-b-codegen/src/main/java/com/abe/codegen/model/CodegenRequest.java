package com.abe.codegen.model;

import com.abe.codegen.policy.AttributeRules;
import com.abe.codegen.policy.PolicyNode;
import com.abe.codegen.policy.PolicyParser;
import java.util.List;
import java.util.Objects;

/** 内部请求：策略在创建时解析，属性列表作不可变拷贝。 */
public record CodegenRequest(PolicyNode policy, List<String> attributes, String plaintext) {
    public CodegenRequest {
        Objects.requireNonNull(policy, "policy");
        attributes = AttributeRules.validateAttributes(attributes);
        Objects.requireNonNull(plaintext, "plaintext");
    }

    public static CodegenRequest from(String policy, List<String> attributes, String plaintext) {
        return new CodegenRequest(new PolicyParser().parse(policy), attributes, plaintext);
    }
}
