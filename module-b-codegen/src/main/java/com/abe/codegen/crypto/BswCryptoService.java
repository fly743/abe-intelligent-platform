package com.abe.codegen.crypto;

import co.junwei.bswabe.Bswabe;
import co.junwei.bswabe.BswabeCphKey;
import co.junwei.bswabe.BswabeMsk;
import co.junwei.bswabe.BswabePrv;
import co.junwei.bswabe.BswabePub;
import co.junwei.bswabe.SerializeUtils;
import com.abe.codegen.policy.AttributeRules;
import com.abe.codegen.policy.BswPolicyConverter;
import com.abe.codegen.policy.PolicyNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** BSW 与 AES-GCM 适配；敏感密钥字节的保护和存储由调用方负责。 */
public final class BswCryptoService {
    private static final byte[] INFO = "abe-bsw-aes-gcm-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PLAINTEXT = 1024 * 1024;
    private final BswabePub publicKey;
    private final BswabeMsk masterKey;
    private final byte[] systemId;
    private final SecureRandom random = new SecureRandom();
    private final BswPolicyConverter converter = new BswPolicyConverter();

    public BswCryptoService() {
        publicKey = new BswabePub(); masterKey = new BswabeMsk();
        Bswabe.setup(publicKey, masterKey);
        systemId = BswPersistence.id(SerializeUtils.serializeBswabePub(publicKey));
    }

    private BswCryptoService(BswabePub pub, BswabeMsk msk, byte[] id) {
        publicKey = pub; masterKey = msk; systemId = id;
    }

    public synchronized byte[] exportPublicKey() {
        return BswPersistence.pack(1, systemId, SerializeUtils.serializeBswabePub(publicKey));
    }

    /** 返回未加密的敏感数据；调用方必须使用受保护的密钥存储。 */
    public synchronized byte[] exportMasterKey() {
        if (masterKey == null) throw new IllegalStateException("当前实例没有主密钥");
        byte[] serialized = SerializeUtils.serializeBswabeMsk(masterKey);
        try { return BswPersistence.pack(2, systemId, serialized); }
        finally { Arrays.fill(serialized, (byte) 0); }
    }

    /** 导出口令保护的主密钥；调用方负责清理口令数组。 */
    public synchronized byte[] exportProtectedMasterKey(char[] password) throws GeneralSecurityException {
        byte[] plain = exportMasterKey();
        try { return BswKeyProtection.protect(plain, 2, password); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public static BswCryptoService restoreProtected(byte[] publicData, byte[] protectedMaster, char[] password)
            throws GeneralSecurityException {
        byte[] plain = BswKeyProtection.unprotect(protectedMaster, 2, password);
        try { return restore(publicData, plain); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    /** 获取公开参数指纹；指纹本身仍需通过独立可信渠道确认。 */
    public static byte[] publicKeyFingerprint(byte[] publicData) {
        byte[] pubBytes = BswPersistence.unpack(publicData, 1, null);
        BswBinaryValidator.publicKey(pubBytes);
        byte[] id = BswPersistence.id(pubBytes);
        BswPersistence.unpack(publicData, 1, id);
        return id;
    }

    /** 在调用方提供独立可信指纹时，拒绝不匹配的公开参数。 */
    public static void verifyPublicKeyFingerprint(byte[] publicData, byte[] trustedFingerprint) {
        if (trustedFingerprint == null || trustedFingerprint.length != 32
                || !MessageDigest.isEqual(publicKeyFingerprint(publicData), trustedFingerprint)) {
            throw new IllegalArgumentException("公开参数与可信指纹不匹配");
        }
    }

    public static BswCryptoService restore(byte[] publicData, byte[] masterData) {
        byte[] pubBytes = BswPersistence.unpack(publicData, 1, null);
        BswBinaryValidator.publicKey(pubBytes);
        byte[] id = BswPersistence.id(pubBytes);
        BswPersistence.unpack(publicData, 1, id);
        byte[] masterBytes = masterData == null ? null : BswPersistence.unpack(masterData, 2, id);
        try {
            if (masterBytes != null) BswBinaryValidator.masterKey(masterBytes);
            BswabePub pub = SerializeUtils.unserializeBswabePub(pubBytes);
            BswabeMsk msk = masterBytes == null ? null : SerializeUtils.unserializeBswabeMsk(pub, masterBytes);
            return new BswCryptoService(pub, msk, id);
        } finally { if (masterBytes != null) Arrays.fill(masterBytes, (byte) 0); }
    }

    public synchronized byte[] exportUserKey(UserKey key) {
        if (key == null || key.owner != this) throw new IllegalArgumentException("密钥不属于当前系统实例");
        byte[] serialized = SerializeUtils.serializeBswabePrv(key.value);
        try { return BswPersistence.pack(3, systemId, serialized); }
        finally { Arrays.fill(serialized, (byte) 0); }
    }

    public synchronized byte[] exportProtectedUserKey(UserKey key, char[] password) throws GeneralSecurityException {
        byte[] plain = exportUserKey(key);
        try { return BswKeyProtection.protect(plain, 3, password); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public synchronized UserKey importProtectedUserKey(byte[] protectedKey, char[] password) throws GeneralSecurityException {
        byte[] plain = BswKeyProtection.unprotect(protectedKey, 3, password);
        try { return importUserKey(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public synchronized UserKey importUserKey(byte[] encoded) {
        byte[] bytes = BswPersistence.unpack(encoded, 3, systemId);
        try {
            BswBinaryValidator.userKey(bytes);
            return new UserKey(this, SerializeUtils.unserializeBswabePrv(publicKey, bytes));
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    public synchronized byte[] exportCiphertext(EncryptedData data) {
        if (data == null || data.owner != this) throw new IllegalArgumentException("密文不属于当前系统实例");
        return BswPersistence.packCiphertext(systemId, data.policyCiphertext, data.salt, data.nonce, data.data);
    }

    public synchronized EncryptedData importCiphertext(byte[] encoded) {
        byte[][] fields = BswPersistence.unpackCiphertext(encoded, systemId);
        BswBinaryValidator.ciphertext(fields[0]);
        return new EncryptedData(this, fields[0], fields[1], fields[2], fields[3]);
    }

    public synchronized UserKey generateKey(List<String> attributes) throws GeneralSecurityException {
        if (masterKey == null) throw new IllegalStateException("当前实例没有主密钥");
        String[] encoded = AttributeRules.validateAttributes(attributes).stream().map(converter::encodeAttribute).toArray(String[]::new);
        return new UserKey(this, Bswabe.keygen(publicKey, masterKey, encoded));
    }

    public synchronized EncryptedData encrypt(PolicyNode policy, byte[] plaintext) throws Exception {
        if (plaintext == null || plaintext.length > MAX_PLAINTEXT) throw new IllegalArgumentException("明文不能为 null 且不能超过 1 MiB");
        BswabeCphKey wrapped = Bswabe.enc(publicKey, converter.convert(policy));
        if (wrapped == null || wrapped.cph == null || wrapped.key == null) throw new GeneralSecurityException("密钥封装失败");
        byte[] policyCiphertext = SerializeUtils.bswabeCphSerialize(wrapped.cph);
        BswBinaryValidator.ciphertext(policyCiphertext);
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] key = deriveKey(wrapped.key.toBytes(), salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(policyCiphertext);
            return new EncryptedData(this, policyCiphertext, salt, nonce, cipher.doFinal(plaintext));
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public synchronized byte[] decrypt(UserKey key, EncryptedData data) throws GeneralSecurityException {
        if (key == null || data == null || key.owner != this || data.owner != this) throw new IllegalArgumentException("密钥和密文须属于当前系统实例");
        BswBinaryValidator.ciphertext(data.policyCiphertext);
        var ciphertext = SerializeUtils.bswabeCphUnserialize(publicKey, data.policyCiphertext);
        var recovered = Bswabe.dec(publicKey, key.value, ciphertext);
        if (!recovered.b || recovered.e == null) throw new PolicyNotSatisfiedException();
        byte[] derived = deriveKey(recovered.e.toBytes(), data.salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, data.nonce));
            cipher.updateAAD(data.policyCiphertext);
            return cipher.doFinal(data.data);
        } finally { Arrays.fill(derived, (byte) 0); }
    }

    private static byte[] deriveKey(byte[] material, byte[] salt) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(material);
        try {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(INFO);
            return mac.doFinal(new byte[]{1});
        } finally { Arrays.fill(prk, (byte) 0); Arrays.fill(material, (byte) 0); }
    }

    public static final class UserKey {
        private final BswCryptoService owner;
        private final BswabePrv value;
        private UserKey(BswCryptoService owner, BswabePrv value) { this.owner = owner; this.value = value; }
    }
    public static final class EncryptedData {
        private final BswCryptoService owner;
        private final byte[] policyCiphertext, salt, nonce, data;
        private EncryptedData(BswCryptoService owner, byte[] policyCiphertext, byte[] salt, byte[] nonce, byte[] data) {
            this.owner = owner; this.policyCiphertext = policyCiphertext; this.salt = salt; this.nonce = nonce; this.data = data;
        }
    }
    public static final class PolicyNotSatisfiedException extends GeneralSecurityException {
        public PolicyNotSatisfiedException() { super("用户属性不满足访问策略"); }
    }
}
