package com.abe.codegen.policy;

/** position 为从零开始的 UTF-16 字符偏移；-1 表示非策略字符串错误。 */
public class PolicyException extends IllegalArgumentException {
    private final int position;

    public PolicyException(String message, int position) {
        super(position < 0 ? message : message + "（位置 " + (position + 1) + "）");
        this.position = position;
    }

    public int getPosition() { return position; }
}
