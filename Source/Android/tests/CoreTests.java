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
        check(PairUri.parse(q.toString()).id.equals(q.id),"QR round trip");check(q.linkSsid==null,"QR without a direct link");
        PairUri linked=PairUri.parse(example+"&ws=DIRECT-HopDrop-7K2Q&wk=pass%20word%2B12&wa=192.168.137.1");
        check("DIRECT-HopDrop-7K2Q".equals(linked.linkSsid)&&"pass word+12".equals(linked.linkKey)&&"192.168.137.1".equals(linked.linkIp),"QR direct link "+linked.linkKey);
        check("pass word+12".equals(PairUri.parse(linked.toString()).linkKey),"QR direct link round trip");
        check(PairUri.parse(example+"&ws=x&wk=short&wa=192.168.137.1").linkSsid==null,"QR invalid direct link ignored");
        check(PairUri.parse(example+"&ws=DIRECT-HopDrop-7K2Q&wk=%ZZ&wa=192.168.137.1").linkSsid==null,"QR malformed link escape ignored");
        check(PairUri.parse(example+"&ws=DIRECT-HopDrop-7K2Q&wk=abcdefghijklmnopqrst").linkSsid==null,"QR incomplete link ignored");}
    private static void json(){Map<String,Object> m=Json.obj("type","hello","n","a\"b\n📁","p",2,"paired",true,"files",Arrays.asList(Json.obj("i",0,"name","α.txt")));
        Map<String,Object> got=Json.parseObject(Json.string(m));check(Json.string(got).equals(Json.string(m)),"JSON round trip");
        try{Json.parseObject("{\"type\":}");throw new AssertionError("Invalid JSON accepted");}catch(IllegalArgumentException expected){count++;}}
    private static void framing()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();Protocol.send(out,"type","ping");byte[] exact=Protocol.unhex("010000000f7b2274797065223a2270696e67227d");check(Arrays.equals(out.toByteArray(),exact),"ping frame");
        check(Protocol.message(new ByteArrayInputStream(out.toByteArray())).get("type").equals("ping"),"read frame");
        for(byte[] wrong:new byte[][]{{3,0,0,0,1,0},{1,0,0,0,0},{2,0,16,0,1},{1,0,0,0,2,123}}){try{Protocol.read(new ByteArrayInputStream(wrong));throw new AssertionError("Bad frame accepted");}catch(IOException expected){count++;}}
        byte[] data=new byte[Protocol.MAX_DATA];Protocol.write(out,2,data);check(Protocol.read(new ByteArrayInputStream(Arrays.copyOfRange(out.toByteArray(),exact.length,out.size()))).body.length==data.length,"max data");}
    private static void platforms()throws Exception{
        String id="a".repeat(64),token="AAECAwQFBgcICQoLDA0ODw";
        for(String platform:new String[]{"windows","android","linux","future9","a","12345678901234567890"}){
            String uri=new PairUri(id,"Computer",platform,7410,Arrays.asList("192.168.1.5"),token).toString();
            PairUri qr=PairUri.parse(uri);check(qr.platform.equals(platform)&&qr.linkSsid==null,"QR platform "+platform);
            check(PairUri.parse(qr.toString()).platform.equals(platform),"QR platform round trip "+platform);
            ByteArrayOutputStream out=new ByteArrayOutputStream();Protocol.hello(out,id,"Computer",platform,false);
            Map<String,Object> hello=Protocol.checkHello(Protocol.message(new ByteArrayInputStream(out.toByteArray())),id);
            check(Json.str(hello,"platform").equals(platform)&&Json.num(hello,"proto")==2,"hello platform "+platform);
            check(!hello.containsKey("link"),"no implied direct link "+platform);
            for(String type:new String[]{"announce","query","bye"}){
                Map<String,Object> message=Protocol.discovery(Json.string(Json.obj("hopdrop",2,"type",type,"id",id,"name",type.equals("bye")?"":"Computer","pl",platform,"p",7410,"rx",!type.equals("bye"))));
                check(Json.str(message,"pl").equals(platform),"discovery "+type+" platform "+platform);
            }
        }
        for(String platform:new String[]{"","Linux","future-os","future_os","future os","é","a".repeat(21),"linux\n"}){
            String uri=new PairUri(id,"Computer",platform,7410,Arrays.asList("192.168.1.5"),token).toString();
            try{PairUri.parse(uri);throw new AssertionError("Bad QR platform accepted: "+platform);}catch(IllegalArgumentException expected){count++;}
            Map<String,Object> hello=Json.obj("type","hello","proto",2,"id",id,"name","Computer","platform",platform,"app",Protocol.APP,"paired",false);
            try{Protocol.checkHello(Json.parseObject(Json.string(hello)),id);throw new AssertionError("Bad hello platform accepted: "+platform);}catch(IOException expected){check(expected.getMessage().startsWith("protocol_error"),"invalid hello platform error");}
            try{Protocol.discovery(Json.string(Json.obj("hopdrop",2,"type","announce","id",id,"name","Computer","pl",platform,"p",7410,"rx",true)));throw new AssertionError("Bad discovery platform accepted: "+platform);}catch(IllegalArgumentException expected){count++;}
        }
        Map<String,Object> hello=Json.obj("type","hello","proto",2,"id",id,"name","Computer","platform","linux");
        try{Protocol.checkHello(hello,"b".repeat(64));throw new AssertionError("Linux identity mismatch accepted");}catch(IOException expected){check(expected.getMessage().startsWith("identity_mismatch"),"Linux identity still checked");}
        hello.put("proto",3);
        try{Protocol.checkHello(hello,id);throw new AssertionError("Linux protocol 3 accepted");}catch(IOException expected){check(expected.getMessage().startsWith("unsupported_version"),"Linux protocol still checked");}
    }
    private static void meter(){long s=1_000_000_000L;TransferMeter m=new TransferMeter(100L*1024*1024,0);check(m.secondsLeft(0,0)==-1,"meter unknown at start");
        m.update(10L*1024*1024,s);check(Math.abs(m.speed(10L*1024*1024,s)-10L*1024*1024)<1,"meter first sample");check(m.secondsLeft(10L*1024*1024,s)==9,"meter eta");
        m.update(12L*1024*1024,2*s);double smoothed=m.speed(12L*1024*1024,2*s);check(smoothed<10L*1024*1024&&smoothed>2L*1024*1024,"meter smoothing");
        check(m.due(2*s)&&!m.due(2*s+100_000_000L)&&m.due(2*s+300_000_000L),"meter throttle");check(new TransferMeter(-1,0).secondsLeft(5,s)==-1,"meter unknown total");}
    private static void backup(){
        check(BackupRules.kind(BackupRules.PHOTOS,"image/jpeg","photo.bin"),"photo MIME");
        check(BackupRules.kind(BackupRules.VIDEOS,null,"clip.mp4"),"video extension");
        check(BackupRules.kind(BackupRules.DOCUMENTS,"application/octet-stream","report.pdf"),"document extension");
        check(BackupRules.kind(BackupRules.AUDIO,"audio/mpeg","song.bin"),"audio MIME");
        check(!BackupRules.kind(BackupRules.PHOTOS,"audio/mpeg","song.mp3"),"kind excludes audio");
        check(BackupRules.kind(BackupRules.ALL,null,"unknown.xyz"),"all includes unknown files");
        check(BackupRules.inWindow(60,60,360)&&!BackupRules.inWindow(360,60,360),"day window bounds");
        check(BackupRules.inWindow(1439,1380,120)&&BackupRules.inWindow(0,1380,120)&&!BackupRules.inWindow(600,1380,120),"midnight window");
        check(!BackupRules.allowed(true,true,false,false,100,0,0)&&BackupRules.allowed(true,true,true,false,100,0,0),"charger rule");
        check(BackupRules.allowed(true,false,false,true,30,1380,120)&&!BackupRules.allowed(true,false,false,true,600,1380,120),"timed rule");
        check(BackupRules.allowed(true,false,false,false,600,1380,120),"manual check skips window");
        check(!BackupRules.allowed(false,false,true,false,100,0,0),"off rule");
        Map<String,Object> old=BackupRules.legacy("first",true,"content://tree","Camera","abcdef",true,"Up to date",7);
        check(Boolean.TRUE.equals(old.get("on"))&&old.get("kinds").equals(BackupRules.ALL)&&old.get("lastRun").equals(7L)&&old.get("device").equals("abcdef"),"legacy settings migrate");
        check(!BackupRules.sentFile("first","abcdef").equals(BackupRules.sentFile("second","abcdef")),"per-backup sent lists");
        check(!BackupRules.sentFile("first","abcdef").equals(BackupRules.sentFile("first","123456")),"per-device sent lists");
        check(BackupRules.oldSentFile("abcdef").equals("backup-sent-abcdef.txt"),"legacy sent list name");
        check(BackupRules.sentFile("legacy","abcdef").equals(BackupRules.oldSentFile("abcdef")),"migration reuses old sent list");
    }
    public static void main(String[] args)throws Exception{sas();qr();platforms();names();json();framing();meter();backup();System.out.println("PASS core tests: "+count+" checks");}
}
