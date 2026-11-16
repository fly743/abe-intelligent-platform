package com.abe.codegen.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** 口令保护的密钥封装；不保存口令，也不管理文件访问权限。 */
final class BswKeyProtection {
    private static final int MAGIC = 0x41424b50; // ABKP
    private static final int VERSION = 1;
    private static final int ITERATIONS = 600_000;
    private static final int SALT_SIZE = 16;
    private static final int NONCE_SIZE = 12;
    private static final int HEADER = 4 + 1 + 1 + 4 + SALT_SIZE + NONCE_SIZE + 4;
    private static final int MAX_PLAIN = 256 * 1024 + 42;
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] protect(byte[] plain, int type, char[] password) throws GeneralSecurityException {
        checkPassword(password);
        if (plain == null || plain.length > MAX_PLAIN || (type != 2 && type != 3)) throw invalid();
        byte[] salt = new byte[SALT_SIZE], nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER).putInt(MAGIC).put((byte) VERSION).put((byte) type)
                .putInt(ITERATIONS).put(salt).put(nonce).putInt(plain.length + 16).array();
        byte[] key = derive(password, salt, ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(HEADER + encrypted.length).put(header).put(encrypted).array();
        } finally { Arrays.fill(key, (byte) 0); }
    }

    static byte[] unprotect(byte[] encoded, int expectedType, char[] password) throws GeneralSecurityException {
        checkPassword(password);
        if (encoded == null || encoded.length < HEADER + 16 || encoded.length > HEADER + MAX_PLAIN + 16) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(encoded);
        if (b.getInt() != MAGIC || b.get() != VERSION || b.get() != expectedType || (expectedType != 2 && expectedType != 3)) throw invalid();
        int iterations = b.getInt();
        // v1 使用固定参数，避免攻击者通过文件控制计算开销。
        if (iterations != ITERATIONS) throw invalid();
        byte[] salt = new byte[SALT_SIZE], nonce = new byte[NONCE_SIZE]; b.get(salt); b.get(nonce);
        int length = b.getInt();
        if (length < 16 || length != b.remaining()) throw invalid();
        byte[] key = derive(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(encoded, 0, HEADER);
            return cipher.doFinal(encoded, HEADER, length);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static void checkPassword(char[] password) {
        if (password == null || password.length == 0 || password.length > 1024) throw new IllegalArgumentException("口令长度必须为 1 至 1024 个字符");
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("无效或超出限制的受保护密钥数据"); }
}
