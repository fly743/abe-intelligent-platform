import com.abe.codegen.crypto.BswCryptoService;
import com.abe.codegen.crypto.BswProtectedKeyFiles;
import com.abe.codegen.policy.PolicyParser;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** 测试口令仅用于验证；测试目录中仅写入受保护密钥。 */
public final class BswProtectedKeyCheck {
    private static final char[] PASSWORD = "test-only-password".toCharArray();
    interface Check { void run() throws Exception; }
    static void rejected(Check c) throws Exception {
        try { c.run(); } catch (IllegalArgumentException | java.security.GeneralSecurityException | IOException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
    static void save(Path dir, String name, byte[] bytes) throws Exception { BswProtectedKeyFiles.saveNew(dir.resolve(name), bytes); }
    static byte[] read(Path dir, String name) throws Exception { return BswProtectedKeyFiles.read(dir.resolve(name)); }
    static int child(String mode, Path dir) throws Exception {
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"), BswProtectedKeyCheck.class.getName(), mode, dir.toString()).inheritIO().start();
        return p.waitFor();
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Path dir=Files.createTempDirectory("abe-protected-check-");
            try {
                if(child("write",dir)!=0 || child("read",dir)!=0) throw new AssertionError("child failed");
                System.out.println("Protected key file checks passed");
            } finally {
                try(var paths=Files.list(dir)) { for(Path p:paths.toList()) Files.deleteIfExists(p); }
                Files.deleteIfExists(dir);
            }
            return;
        }
        Path dir=Path.of(args[1]);
        if(args[0].equals("write")) {
            var s=new BswCryptoService();
            Files.write(dir.resolve("public"),s.exportPublicKey(),StandardOpenOption.CREATE_NEW);
            save(dir,"master",s.exportProtectedMasterKey(PASSWORD));
            save(dir,"user",s.exportProtectedUserKey(s.generateKey(List.of("A","B")),PASSWORD));
            save(dir,"denied",s.exportProtectedUserKey(s.generateKey(List.of("A")),PASSWORD));
            Files.write(dir.resolve("ciphertext"),s.exportCiphertext(s.encrypt(new PolicyParser().parse("(A AND B) OR (A AND C)"),new byte[]{1,2,3})),StandardOpenOption.CREATE_NEW);
            return;
        }
        byte[] pub=Files.readAllBytes(dir.resolve("public")), master=read(dir,"master"), user=read(dir,"user"), ct=Files.readAllBytes(dir.resolve("ciphertext"));
        byte[] fingerprint=BswCryptoService.publicKeyFingerprint(pub);
        BswCryptoService.verifyPublicKeyFingerprint(pub,fingerprint);
        byte[] foreignPub=new BswCryptoService().exportPublicKey();
        rejected(()->BswCryptoService.verifyPublicKeyFingerprint(foreignPub,fingerprint));
        rejected(()->BswCryptoService.verifyPublicKeyFingerprint(pub,new byte[31]));
        var s=BswCryptoService.restoreProtected(pub,master,PASSWORD);
        if (!Arrays.equals(new byte[]{1,2,3},s.decrypt(s.importProtectedUserKey(user,PASSWORD),s.importCiphertext(ct)))) throw new AssertionError();
        rejected(()->s.decrypt(s.importProtectedUserKey(read(dir,"denied"),PASSWORD),s.importCiphertext(ct)));
        rejected(()->BswCryptoService.restoreProtected(pub,master,"incorrect".toCharArray()));
        rejected(()->s.importProtectedUserKey(user,"incorrect".toCharArray()));
        rejected(()->s.importProtectedUserKey(master,PASSWORD));
        rejected(()->BswCryptoService.restoreProtected(pub,user,PASSWORD));
        rejected(()->BswCryptoService.restoreProtected(new BswCryptoService().exportPublicKey(),master,PASSWORD));
        rejected(()->s.importProtectedUserKey(Arrays.copyOf(user,user.length-1),PASSWORD));
        byte[] modified=user.clone(); modified[modified.length-1]^=1; rejected(()->s.importProtectedUserKey(modified,PASSWORD));
        byte[] salt=user.clone(); salt[10]^=1; rejected(()->s.importProtectedUserKey(salt,PASSWORD));
        byte[] iteration=user.clone(); iteration[9]^=1; rejected(()->s.importProtectedUserKey(iteration,PASSWORD));
        rejected(()->save(dir,"user",user));
        rejected(()->save(dir,"plaintext",s.exportUserKey(s.generateKey(List.of("A")))));
        var sameKey=s.importProtectedUserKey(user,PASSWORD);
        byte[] first=s.exportProtectedUserKey(sameKey,PASSWORD), second=s.exportProtectedUserKey(sameKey,PASSWORD);
        if(Arrays.equals(first,second)) throw new AssertionError("randomized protection expected");
        Arrays.fill(PASSWORD,'\0');
    }
}
