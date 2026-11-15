package com.abe.codegen.policy;

/** 访问策略的不可变语法树。 */
public sealed interface PolicyNode permits PolicyNode.Attribute, PolicyNode.And, PolicyNode.Or {
    record Attribute(String name) implements PolicyNode {
        public Attribute { AttributeRules.validateName(name); }
    }
    record And(PolicyNode left, PolicyNode right) implements PolicyNode {
        public And { java.util.Objects.requireNonNull(left); java.util.Objects.requireNonNull(right); }
    }
    record Or(PolicyNode left, PolicyNode right) implements PolicyNode {
        public Or { java.util.Objects.requireNonNull(left); java.util.Objects.requireNonNull(right); }
    }
}
