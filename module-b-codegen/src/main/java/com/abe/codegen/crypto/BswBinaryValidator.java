package com.abe.codegen.crypto;

import java.nio.ByteBuffer;

/** 上游 BSW 二进制结构的长度预检，不验证群元素的密码学有效性。 */
final class BswBinaryValidator {
    private static final int MAX_TOTAL = 256 * 1024;
    // 固定 Type A 参数下，jPBC 1.2.1 的 G1/G2/GT 和 Zr 编码长度。
    private static final int GROUP_ELEMENT = 128;
    private static final int SCALAR_ELEMENT = 20;
    private static final int MAX_ATTRIBUTE = 129;
    private final ByteBuffer data;
    private int nodes;

    private BswBinaryValidator(byte[] input) {
        if (input == null || input.length > MAX_TOTAL) throw invalid();
        data = ByteBuffer.wrap(input);
    }

    static void publicKey(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        byte[] expected = ("type a\n"
                + "q 87807107996633125224377819847540498158068831994142082"
                + "1102865339926647563088022295707862517942266222142315585"
                + "8769582317459277713367317481324925129998224791\n"
                + "h 12016012264891146079388821366740534204802954401251311"
                + "822919615131047207289359704531102844802183906537786776\n"
                + "r 730750818665451621361119245571504901405976559617\n"
                + "exp2 159\nexp1 107\nsign1 1\nsign0 1\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int length = v.number();
        if (length != expected.length || length > v.data.remaining()) throw invalid();
        for (byte b : expected) if (v.data.get() != b) throw invalid();
        v.element(GROUP_ELEMENT); v.element(GROUP_ELEMENT); v.element(GROUP_ELEMENT); v.element(GROUP_ELEMENT); v.finished();
    }

    static void userKey(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element(GROUP_ELEMENT);
        int count = v.number();
        if (count < 1 || count > 64) throw invalid();
        for (int i = 0; i < count; i++) { v.attribute(); v.element(GROUP_ELEMENT); v.element(GROUP_ELEMENT); }
        v.finished();
    }

    static void masterKey(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element(SCALAR_ELEMENT); v.element(GROUP_ELEMENT); v.finished();
    }

    static void ciphertext(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element(GROUP_ELEMENT); v.element(GROUP_ELEMENT); v.policy(0); v.finished();
    }

    private void policy(int depth) {
        if (depth > 64 || ++nodes > 127) throw invalid();
        int threshold = number();
        int children = number();
        if (children == 0) {
            if (threshold != 1) throw invalid();
            attribute(); element(GROUP_ELEMENT); element(GROUP_ELEMENT);
        } else {
            if (children != 2 || threshold < 1 || threshold > children) throw invalid();
            for (int i = 0; i < children; i++) policy(depth + 1);
        }
    }

    private void attribute() {
        int length = number();
        if (length < 3 || length > MAX_ATTRIBUTE || (length & 1) == 0 || length > data.remaining()) throw invalid();
        if (data.get() != 'a') throw invalid();
        for (int i = 1; i < length; i++) {
            byte b = data.get();
            if (!((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f'))) throw invalid();
        }
    }

    private void element(int expectedLength) {
        int length = number();
        if (length != expectedLength || length > data.remaining()) throw invalid();
        data.position(data.position() + length);
    }

    private int number() {
        if (data.remaining() < Integer.BYTES) throw invalid();
        int value = data.getInt();
        if (value < 0) throw invalid();
        return value;
    }

    private void finished() { if (data.hasRemaining()) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("无效或超出限制的 BSW 二进制数据"); }
}
