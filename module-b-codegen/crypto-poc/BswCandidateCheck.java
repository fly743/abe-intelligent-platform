import co.junwei.bswabe.Bswabe;
import co.junwei.bswabe.BswabeMsk;
import co.junwei.bswabe.BswabePub;
import co.junwei.bswabe.SerializeUtils;
import it.unisa.dia.gas.jpbc.Element;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 验证 BSW 候选方案，不属于正式密码接口。仅用于固定场景的功能核查。 */
public class BswCandidateCheck {
    private static final byte[] MESSAGE = "重复属性策略跨进程验证".getBytes(StandardCharsets.UTF_8);
    private static SecretKeySpec key(Element element) throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(element.toBytes()), "AES");
    }
    private static byte[] crypt(int mode, SecretKeySpec key, byte[] nonce, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(data);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) throw new IllegalArgumentException("write|read directory");
        Path dir = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(dir);
            var pub = new BswabePub();
            var master = new BswabeMsk();
            Bswabe.setup(pub, master);
            var wrapped = Bswabe.enc(pub, "A B 2of2 A C 2of2 1of2");
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Files.write(dir.resolve("public.bin"), SerializeUtils.serializeBswabePub(pub));
            Files.write(dir.resolve("master.bin"), SerializeUtils.serializeBswabeMsk(master));
            Files.write(dir.resolve("ciphertext.bin"), SerializeUtils.bswabeCphSerialize(wrapped.cph));
            Files.write(dir.resolve("nonce.bin"), nonce);
            Files.write(dir.resolve("data.bin"), crypt(Cipher.ENCRYPT_MODE, key(wrapped.key), nonce, MESSAGE));
            System.out.println("write complete");
        } else {
            var pub = SerializeUtils.unserializeBswabePub(Files.readAllBytes(dir.resolve("public.bin")));
            var master = SerializeUtils.unserializeBswabeMsk(pub, Files.readAllBytes(dir.resolve("master.bin")));
            var ciphertext = SerializeUtils.bswabeCphUnserialize(pub, Files.readAllBytes(dir.resolve("ciphertext.bin")));
            byte[] nonce = Files.readAllBytes(dir.resolve("nonce.bin"));
            byte[] data = Files.readAllBytes(dir.resolve("data.bin"));
            String[][] cases = {{"A", "B"}, {"A", "C"}, {"A"}, {"B", "C"}};
            SecretKeySpec validKey = null;
            for (int i = 0; i < cases.length; i++) {
                var generated = Bswabe.keygen(pub, master, cases[i]);
                var restored = SerializeUtils.unserializeBswabePrv(pub, SerializeUtils.serializeBswabePrv(generated));
                var result = Bswabe.dec(pub, restored, ciphertext);
                if (result.b != (i < 2)) throw new IllegalStateException("Unexpected policy result");
                if (result.b) {
                    validKey = key(result.e);
                    if (!Arrays.equals(MESSAGE, crypt(Cipher.DECRYPT_MODE, validKey, nonce, data))) throw new IllegalStateException("Plaintext mismatch");
                }
                System.out.println(Arrays.toString(cases[i]) + (result.b ? ": plaintext matched" : ": rejected"));
            }
            data[0] ^= 1;
            try { crypt(Cipher.DECRYPT_MODE, validKey, nonce, data); throw new IllegalStateException("Tampered data accepted"); }
            catch (AEADBadTagException expected) { System.out.println("modified AES-GCM ciphertext rejected"); }
        }
    }
}
