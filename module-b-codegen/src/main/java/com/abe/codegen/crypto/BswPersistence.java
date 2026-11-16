package com.abe.codegen.crypto;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 有界、版本化的二进制封装；系统标识用于误配检查，不提供真实性。 */
final class BswPersistence {
    private static final int MAGIC = 0x41424542;
    private static final int MAX_BSW = 256 * 1024;
    private static final int MAX_DATA = 1024 * 1024 + 16;
    private static final int HEADER = 4 + 1 + 1 + 32 + 4;

    static byte[] id(byte[] pub) {
        try { return MessageDigest.getInstance("SHA-256").digest(pub); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static byte[] pack(int type, byte[] id, byte[] payload) {
        if (payload == null || payload.length > (type == 4 ? MAX_DATA + MAX_BSW + 64 : MAX_BSW)) throw invalid();
        return ByteBuffer.allocate(HEADER + payload.length).putInt(MAGIC).put((byte) 1).put((byte) type)
                .put(id).putInt(payload.length).put(payload).array();
    }

    static byte[] unpack(byte[] input, int type, byte[] expectedId) {
        int max = type == 4 ? MAX_DATA + MAX_BSW + 64 : MAX_BSW;
        if (input == null || input.length < HEADER || input.length > HEADER + max) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(input);
        if (b.getInt() != MAGIC || b.get() != 1 || b.get() != type) throw invalid();
        byte[] id = new byte[32]; b.get(id);
        if (expectedId != null && !MessageDigest.isEqual(id, expectedId)) throw invalid();
        int length = b.getInt();
        if (length < 0 || length != b.remaining()) throw invalid();
        byte[] result = new byte[length]; b.get(result); return result;
    }

    static byte[] packCiphertext(byte[] id, byte[] policy, byte[] salt, byte[] nonce, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(16 + policy.length + salt.length + nonce.length + data.length);
        for (byte[] field : new byte[][]{policy, salt, nonce, data}) b.putInt(field.length).put(field);
        return pack(4, id, b.array());
    }

    static byte[][] unpackCiphertext(byte[] input, byte[] id) {
        ByteBuffer b = ByteBuffer.wrap(unpack(input, 4, id));
        int[] limits = {MAX_BSW, 32, 12, MAX_DATA};
        byte[][] result = new byte[4][];
        for (int i = 0; i < result.length; i++) {
            if (b.remaining() < 4) throw invalid();
            int length = b.getInt();
            if (length < 0 || length > limits[i] || length > b.remaining()) throw invalid();
            if ((i == 1 && length != 32) || (i == 2 && length != 12) || (i == 3 && length < 16)) throw invalid();
            result[i] = new byte[length]; b.get(result[i]);
        }
        if (b.hasRemaining()) throw invalid();
        return result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("无效、不匹配或超出限制的持久化数据"); }
}
