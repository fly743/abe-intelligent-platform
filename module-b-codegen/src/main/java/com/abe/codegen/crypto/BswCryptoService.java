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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 内存中的 BSW 与 AES-GCM 适配；密钥和密文持久化由后续阶段设计。 */
public final class BswCryptoService {
    private static final byte[] INFO = "abe-bsw-aes-gcm-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PLAINTEXT = 1024 * 1024;
    private final BswabePub publicKey = new BswabePub();
    private final BswabeMsk masterKey = new BswabeMsk();
    private final SecureRandom random = new SecureRandom();
    private final BswPolicyConverter converter = new BswPolicyConverter();

    public BswCryptoService() { Bswabe.setup(publicKey, masterKey); }

    public synchronized UserKey generateKey(List<String> attributes) throws GeneralSecurityException {
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
