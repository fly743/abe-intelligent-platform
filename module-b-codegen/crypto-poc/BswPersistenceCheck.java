import com.abe.codegen.crypto.BswCryptoService;
import com.abe.codegen.policy.PolicyParser;
import java.io.*;
import java.util.*;

/** 独立进程通过管道交换验证数据，不创建明文密钥文件。 */
public final class BswPersistenceCheck {
    interface Check { void run() throws Exception; }
    static void rejected(Check c) throws Exception {
        try { c.run(); } catch (IllegalArgumentException | java.security.GeneralSecurityException e) { return; }
        throw new AssertionError("无效输入未被拒绝");
    }
    static void write(DataOutputStream out, byte[] b) throws IOException { out.writeInt(b.length); out.write(b); }
    static byte[] read(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0 || n > 2_000_000) throw new IOException("invalid length");
        byte[] b = in.readNBytes(n); if (b.length != n) throw new EOFException(); return b;
    }
    static Process start(String phase) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"), BswPersistenceCheck.class.getName(), phase).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
    static byte[] alter(byte[] b, int offset, int value) { byte[] c = b.clone(); c[offset] = (byte)value; return c; }
    static void verify() throws Exception {
        DataInputStream in = new DataInputStream(System.in);
        byte[] pub=read(in), master=read(in), good1=read(in), good2=read(in), bad=read(in), ct=read(in), other=read(in);
        var service=BswCryptoService.restore(pub,master);
        var ciphertext=service.importCiphertext(ct);
        for (byte[] key : new byte[][]{good1,good2}) {
            if (!Arrays.equals("跨进程".getBytes(java.nio.charset.StandardCharsets.UTF_8), service.decrypt(service.importUserKey(key),ciphertext))) throw new AssertionError("解密不匹配");
        }
        rejected(()->service.decrypt(service.importUserKey(bad),ciphertext));
        rejected(()->BswCryptoService.restore(other,master));
        var foreign=BswCryptoService.restore(other,null);
        rejected(()->foreign.importUserKey(good1)); rejected(()->foreign.importCiphertext(ct));
        var publicOnly=BswCryptoService.restore(pub,null);
        byte[] generated=service.exportUserKey(service.generateKey(List.of("A","C")));
        service.decrypt(service.importUserKey(generated),ciphertext);
        try { publicOnly.generateKey(List.of("A")); throw new AssertionError(); } catch (IllegalStateException expected) { }
        for (int i=0;i<4;i++) {
            final int type=i;
            byte[] original=new byte[][]{pub,master,good1,ct}[i];
            for (int length : new int[]{0, 1, 41, 42, original.length-1}) {
                byte[] shortData=Arrays.copyOf(original,length);
                rejected(()->load(service,pub,type,shortData));
            }
            rejected(()->load(service,pub,type,alter(original,4,2)));
            rejected(()->load(service,pub,type,alter(original,5,99)));
            byte[] oversized=original.clone(); Arrays.fill(oversized,38,42,(byte)0x7f);
            rejected(()->load(service,pub,type,oversized));
            byte[] extra=Arrays.copyOf(original,original.length+1);
            rejected(()->load(service,pub,type,extra));
        }
        // 用户密钥第一个群元素长度；密文载荷第一个 BSW 群元素长度。
        for (int n : new int[]{0,1,127,129,255}) {
            rejected(()->service.importUserKey(alter(good1,45,n)));
            rejected(()->service.importCiphertext(alter(ct,49,n)));
        }
        byte[] broken=ct.clone(); broken[broken.length-1]^=1;
        rejected(()->service.decrypt(service.importUserKey(good1),service.importCiphertext(broken)));
        byte[] malformed=pub.clone(); malformed[46]^=1;
        rejected(()->BswCryptoService.restore(malformed,null));
        // 重算标识，确保固定配对描述检查本身会拒绝修改后的数据。
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(malformed,42,malformed.length));
        System.arraycopy(digest,0,malformed,6,digest.length);
        rejected(()->BswCryptoService.restore(malformed,null));
        // 分别篡改主密钥的标量长度、用户密钥属性数和密文策略树的子节点数。
        rejected(()->BswCryptoService.restore(pub,alter(master,45,19)));
        byte[] count=good1.clone(); Arrays.fill(count,174,178,(byte)0x7f);
        rejected(()->service.importUserKey(count));
        byte[] children=ct.clone(); Arrays.fill(children,314,318,(byte)0x7f);
        rejected(()->service.importCiphertext(children));
        System.out.println("Cross-process persistence checks passed");
    }
    static void load(BswCryptoService s, byte[] pub, int type, byte[] b) throws Exception {
        switch(type) { case 0 -> BswCryptoService.restore(b,null); case 1 -> BswCryptoService.restore(pub,b); case 2 -> s.importUserKey(b); case 3 -> s.importCiphertext(b); default -> throw new AssertionError(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length==0) {
            Process writer=start("write"); byte[] bytes=writer.getInputStream().readAllBytes();
            if(writer.waitFor()!=0) throw new AssertionError("写入进程失败");
            Process reader=start("read"); try(OutputStream out=reader.getOutputStream()){out.write(bytes);} Arrays.fill(bytes,(byte)0);
            reader.getInputStream().transferTo(System.out); if(reader.waitFor()!=0) throw new AssertionError("读取进程失败"); return;
        }
        if(args[0].equals("read")) { verify(); return; }
        var s=new BswCryptoService(); var out=new DataOutputStream(System.out);
        write(out,s.exportPublicKey()); write(out,s.exportMasterKey());
        write(out,s.exportUserKey(s.generateKey(List.of("A","B")))); write(out,s.exportUserKey(s.generateKey(List.of("A","C"))));
        write(out,s.exportUserKey(s.generateKey(List.of("A"))));
        write(out,s.exportCiphertext(s.encrypt(new PolicyParser().parse("(A AND B) OR (A AND C)"),"跨进程".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        write(out,new BswCryptoService().exportPublicKey()); out.flush();
    }
}
