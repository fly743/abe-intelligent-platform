package com.abe.codegen.policy;

import java.util.Locale;

/** AND 优先于 OR；运算符忽略大小写，属性区分大小写。 */
public final class PolicyParser {
    public static final int MAX_LENGTH = 4096;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_LEAVES = 64;

    public PolicyNode parse(String expression) {
        if (expression == null || expression.isBlank()) throw new PolicyException("策略不能为空", 0);
        if (expression.length() > MAX_LENGTH) throw new PolicyException("策略长度不能超过 4096", MAX_LENGTH);
        return new State(expression).parse();
    }

    private static final class State {
        private final String input;
        private int pos;
        private int leaves;
        State(String input) { this.input = input; }

        PolicyNode parse() {
            PolicyNode result = parseOr(0);
            skipSpaces();
            if (pos != input.length()) throw new PolicyException("存在多余的内容或缺少运算符", pos);
            return result;
        }
        PolicyNode parseOr(int depth) {
            PolicyNode left = parseAnd(depth);
            while (consumeOperator("OR")) left = new PolicyNode.Or(left, parseAnd(depth));
            return left;
        }
        PolicyNode parseAnd(int depth) {
            PolicyNode left = parsePrimary(depth);
            while (consumeOperator("AND")) left = new PolicyNode.And(left, parsePrimary(depth));
            return left;
        }
        PolicyNode parsePrimary(int depth) {
            skipSpaces();
            if (pos == input.length()) throw new PolicyException("缺少属性或括号表达式", pos);
            if (input.charAt(pos) == '(') {
                if (depth >= MAX_DEPTH) throw new PolicyException("括号嵌套不能超过 32 层", pos);
                pos++;
                PolicyNode result = parseOr(depth + 1);
                skipSpaces();
                if (pos == input.length() || input.charAt(pos) != ')') throw new PolicyException("缺少右括号", pos);
                pos++;
                return result;
            }
            int start = pos;
            while (pos < input.length() && isNameChar(input.charAt(pos))) pos++;
            if (start == pos) throw new PolicyException("此处需要属性或左括号", pos);
            String name = input.substring(start, pos);
            try { AttributeRules.validateName(name); }
            catch (PolicyException e) { throw new PolicyException(e.getMessage(), start); }
            if (++leaves > MAX_LEAVES) throw new PolicyException("策略属性出现次数不能超过 64", start);
            return new PolicyNode.Attribute(name);
        }
        boolean consumeOperator(String operator) {
            skipSpaces();
            int end = pos;
            while (end < input.length() && isNameChar(input.charAt(end))) end++;
            if (input.substring(pos, end).toUpperCase(Locale.ROOT).equals(operator)) { pos = end; return true; }
            return false;
        }
        void skipSpaces() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
        boolean isNameChar(char c) { return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-'; }
    }
}
