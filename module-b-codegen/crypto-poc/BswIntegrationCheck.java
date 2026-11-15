import co.junwei.bswabe.*;
import com.abe.codegen.policy.*;
import java.util.*;
public class BswIntegrationCheck {
 public static void main(String[] args) throws Exception {
  var pub=new BswabePub(); var msk=new BswabeMsk(); Bswabe.setup(pub,msk);
  var converter=new BswPolicyConverter(); var parser=new PolicyParser();
  var cases=new String[]{"(A AND B) OR (A AND C)","(A OR B) AND (A OR C)","professor AND 2of2"};
  var attrs=new String[][]{{"A","B","C"},{"A","B","C"},{"professor","2of2"}};
  for(int t=0;t<cases.length;t++) {
   var source=parser.parse(cases[t]); var wrapped=Bswabe.enc(pub,converter.convert(source));
   for(int mask=0;mask<(1<<attrs[t].length);mask++) {
    var raw=new ArrayList<String>(); for(int i=0;i<attrs[t].length;i++)if((mask & (1<<i))!=0)raw.add(attrs[t][i]);

    boolean expected=eval(source,new HashSet<>(raw));
    var encoded=raw.stream().map(converter::encodeAttribute).toArray(String[]::new);
    var out=Bswabe.dec(pub,Bswabe.keygen(pub,msk,encoded),wrapped.cph);
    if(out.b!=expected || (expected && !out.e.isEqual(wrapped.key)))throw new AssertionError(cases[t]+raw);
   }
   System.out.println(cases[t]+" all attribute subsets passed");
  }
 }
 static boolean eval(PolicyNode n, Set<String> a) { if(n instanceof PolicyNode.Attribute x)return a.contains(x.name()); if(n instanceof PolicyNode.And x)return eval(x.left(),a)&&eval(x.right(),a); var x=(PolicyNode.Or)n; return eval(x.left(),a)||eval(x.right(),a); }
}
