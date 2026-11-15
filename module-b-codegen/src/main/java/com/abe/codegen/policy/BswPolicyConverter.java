package com.abe.codegen.policy;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** 将策略树转换为 BSW 实现使用的后缀门限表达式。 */
public final class BswPolicyConverter {
    public String convert(PolicyNode policy) {
        StringBuilder output = new StringBuilder();
        int[] leaves = {0};
        append(Objects.requireNonNull(policy, "policy"), output, 0, leaves);
        return output.toString();
    }

    /** 策略和用户密钥必须使用相同编码，避免上游将含 of 的属性识别成门限符。 */
    public String encodeAttribute(String attribute) {
        AttributeRules.validateName(attribute);
        return "a" + HexFormat.of().formatHex(attribute.getBytes(StandardCharsets.US_ASCII));
    }

    private void append(PolicyNode node, StringBuilder output, int depth, int[] leaves) {
        if (depth > PolicyParser.MAX_LEAVES) throw new PolicyException("策略树过深", -1);
        if (node instanceof PolicyNode.Attribute attribute) {
            if (++leaves[0] > PolicyParser.MAX_LEAVES) throw new PolicyException("策略属性出现次数不能超过 64", -1);
            appendToken(output, encodeAttribute(attribute.name()));
        } else if (node instanceof PolicyNode.And and) {
            append(and.left(), output, depth + 1, leaves);
            append(and.right(), output, depth + 1, leaves);
            appendToken(output, "2of2");
        } else if (node instanceof PolicyNode.Or or) {
            append(or.left(), output, depth + 1, leaves);
            append(or.right(), output, depth + 1, leaves);
            appendToken(output, "1of2");
        } else {
            throw new IllegalArgumentException("不支持的策略节点");
        }
    }

    private void appendToken(StringBuilder output, String token) {
        if (!output.isEmpty()) output.append(' ');
        output.append(token);
    }
}
