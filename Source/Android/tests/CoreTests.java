package com.hop.drop.tests;

import com.hop.drop.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CoreTests {
    private static int count;
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);count++;}
    private static void names(){String[][] rows={{"report.pdf","report.pdf"},{"../evil.txt","evil.txt"},{"C:\\Windows\\x.dll","x.dll"},{"con.txt","_con.txt"},{"COM1","_COM1"},{"what?.txt","what_.txt"},{"name. ","name"},{"","file"},{"..","file"},{"تقرير 📁.pdf","تقرير 📁.pdf"},{"\u0001a.txt","_a.txt"},{"a".repeat(250)+".txt","a".repeat(176)+".txt"}};
        for(String[] row:rows)check(FileNames.sanitize(row[0]).equals(row[1]),"filename "+row[0]);
        check(FileNames.unique("README",n->n.equals("README")).equals("README (1)"),"duplicate README");
        check(FileNames.unique(".hidden",n->n.equals(".hidden")).equals(".hidden (1)"),"duplicate hidden");
        String[][] texts={{"Meeting at 5 pm.\nBring the slides","Meeting at 5 pm.txt"},{"  \n https://example.com/a","Link.txt"},{"   ","Text.txt"},{"???","Text.txt"},
            {"The quick brown fox jumps over the lazy dog again and again","The quick brown fox jumps over the lazy.txt"},{"a/b: c","a_b_ c.txt"}};
        for(String[] row:texts)check(FileNames.forText(row[0]).equals(row[1]),"text name "+row[0]+" -> "+FileNames.forText(row[0]));}
    private static void sas(){String[] fpI={"11".repeat(32),"59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419"};
        String[] fpR={"22".repeat(32),"6dcde155f1f900e157d25bb5c24b1a54d71ea35b78661ed9b1a8be3532c3d4f5"};
        String[] ni={"33".repeat(32),"90c25e389b9cdf641350c7572c1d030f051747d44809a6ec1243eb9227fc0c96"};
        String[] nr={"44".repeat(32),"433d46ffcc5cd2238a19974a19da7eb21770b0d908ed20ebb2b91ceb230b6d40"};
        String[] commits={"6935596fda9b27748a1b439b2172499fbf95b23fc48787576c9def67405cf3b6","5e8269ab296d63bb36e4f46c832c56140eb138618809dc51c4de68652dc46bd5"};String[] codes={"219471","447251"};
        for(int i=0;i<2;i++){check(Sas.commit(Protocol.unhex(fpR[i]),Protocol.unhex(fpI[i]),Protocol.unhex(nr[i])).equals(commits[i]),"SAS commit "+i);
            check(Sas.code(Protocol.unhex(fpI[i]),Protocol.unhex(fpR[i]),Protocol.unhex(ni[i]),Protocol.unhex(nr[i])).equals(codes[i]),"SAS code "+i);}}
    private static void qr(){String example="hopdrop://pair?v=2&id=59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419&n=LOQ%20Laptop&pl=windows&p=7410&a=192.168.1.5,192.168.137.1&t=AAECAwQFBgcICQoLDA0ODw";
        PairUri q=PairUri.parse(example);check(q.name.equals("LOQ Laptop")&&q.addresses.size()==2&&q.port==7410&&Arrays.equals(Base64.getUrlDecoder().decode(q.token),new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}),"QR example");
        check(PairUri.parse(q.toString()).id.equals(q.id),"QR round trip");}
    private static void json(){Map<String,Object> m=Json.obj("type","hello","n","a\"b\n📁","p",2,"paired",true,"files",Arrays.asList(Json.obj("i",0,"name","α.txt")));
        Map<String,Object> got=Json.parseObject(Json.string(m));check(Json.string(got).equals(Json.string(m)),"JSON round trip");
        try{Json.parseObject("{\"type\":}");throw new AssertionError("Invalid JSON accepted");}catch(IllegalArgumentException expected){count++;}}
    private static void framing()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();Protocol.send(out,"type","ping");byte[] exact=Protocol.unhex("010000000f7b2274797065223a2270696e67227d");check(Arrays.equals(out.toByteArray(),exact),"ping frame");
        check(Protocol.message(new ByteArrayInputStream(out.toByteArray())).get("type").equals("ping"),"read frame");
        for(byte[] wrong:new byte[][]{{3,0,0,0,1,0},{1,0,0,0,0},{2,0,16,0,1},{1,0,0,0,2,123}}){try{Protocol.read(new ByteArrayInputStream(wrong));throw new AssertionError("Bad frame accepted");}catch(IOException expected){count++;}}
        byte[] data=new byte[Protocol.MAX_DATA];Protocol.write(out,2,data);check(Protocol.read(new ByteArrayInputStream(Arrays.copyOfRange(out.toByteArray(),exact.length,out.size()))).body.length==data.length,"max data");}
    private static void meter(){long s=1_000_000_000L;TransferMeter m=new TransferMeter(100L*1024*1024,0);check(m.secondsLeft(0,0)==-1,"meter unknown at start");
        m.update(10L*1024*1024,s);check(Math.abs(m.speed(10L*1024*1024,s)-10L*1024*1024)<1,"meter first sample");check(m.secondsLeft(10L*1024*1024,s)==9,"meter eta");
        m.update(12L*1024*1024,2*s);double smoothed=m.speed(12L*1024*1024,2*s);check(smoothed<10L*1024*1024&&smoothed>2L*1024*1024,"meter smoothing");
        check(m.due(2*s)&&!m.due(2*s+100_000_000L)&&m.due(2*s+300_000_000L),"meter throttle");check(new TransferMeter(-1,0).secondsLeft(5,s)==-1,"meter unknown total");}
    public static void main(String[] args)throws Exception{sas();qr();names();json();framing();meter();System.out.println("PASS core tests: "+count+" checks");}
}
