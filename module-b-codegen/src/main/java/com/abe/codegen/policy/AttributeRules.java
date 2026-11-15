package com.abe.codegen.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 首版属性命名与数量约束。 */
public final class AttributeRules {
    public static final int MAX_ATTRIBUTES = 64;
    public static final int MAX_NAME_LENGTH = 64;
    private AttributeRules() { }

    public static void validateName(String name) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !name.matches("[A-Za-z0-9_][A-Za-z0-9_-]*")) {
            throw new PolicyException("属性须为 1 至 64 位 ASCII 字母、数字、下划线或连字符，且不能以连字符开头", -1);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("AND") || upper.equals("OR")) throw new PolicyException("属性不能使用保留字 AND 或 OR", -1);
    }

    public static List<String> validateAttributes(List<String> attributes) {
        if (attributes == null || attributes.isEmpty() || attributes.size() > MAX_ATTRIBUTES) {
            throw new PolicyException("用户属性数量须为 1 至 64", -1);
        }
        var unique = new LinkedHashSet<String>();
        for (String attribute : attributes) {
            validateName(attribute);
            if (!unique.add(attribute)) throw new PolicyException("用户属性重复：" + attribute, -1);
        }
        return List.copyOf(unique);
    }
}
