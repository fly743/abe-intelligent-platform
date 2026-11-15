package com.abe.codegen.crypto;

import java.nio.ByteBuffer;

/** 上游 BSW 二进制结构的长度预检，不验证群元素的密码学有效性。 */
final class BswBinaryValidator {
    private static final int MAX_TOTAL = 256 * 1024;
    private static final int MAX_ELEMENT = 4096;
    private static final int MAX_ATTRIBUTE = 129;
    private final ByteBuffer data;
    private int nodes;

    private BswBinaryValidator(byte[] input) {
        if (input == null || input.length > MAX_TOTAL) throw invalid();
        data = ByteBuffer.wrap(input);
    }

    static void userKey(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element();
        int count = v.number();
        if (count < 1 || count > 64) throw invalid();
        for (int i = 0; i < count; i++) { v.attribute(); v.element(); v.element(); }
        v.finished();
    }

    static void masterKey(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element(); v.element(); v.finished();
    }

    static void ciphertext(byte[] input) {
        BswBinaryValidator v = new BswBinaryValidator(input);
        v.element(); v.element(); v.policy(0); v.finished();
    }

    private void policy(int depth) {
        if (depth > 64 || ++nodes > 127) throw invalid();
        int threshold = number();
        int children = number();
        if (children == 0) {
            if (threshold != 1) throw invalid();
            attribute(); element(); element();
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

    private void element() {
        int length = number();
        if (length < 1 || length > MAX_ELEMENT || length > data.remaining()) throw invalid();
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
