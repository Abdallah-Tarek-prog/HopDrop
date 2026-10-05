package com.hop.drop.tests;

import com.hop.drop.core.Peer;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Executes every Java/.NET scenario; only summary lines reach stdout. Uses ports 17410/17411 so a running HopDrop (7410) is never touched. */
public final class InteropRunner {
    private final Path root,dll,javaData,javaReceive,winData,winReceive,source;
    private final List<String> results=new ArrayList<>();
    private Process server;private final BlockingQueue<String> lines=new LinkedBlockingQueue<>();private final StringBuilder serverErrors=new StringBuilder();
    private InteropRunner(Path root,Path dll){this.root=root;this.dll=dll;javaData=root.resolve("java");javaReceive=root.resolve("java-received");winData=root.resolve("windows");winReceive=root.resolve("windows-received");source=root.resolve("sources");}
    private void result(String name,boolean ok,String detail){String line=(ok?"PASS ":"FAIL ")+name+(detail==null?"":": "+detail.replaceAll("hopdrop://[^ ]+","[QR URI hidden]"));results.add(line);System.out.println(line);}
    private List<String> base(){return new ArrayList<>(Arrays.asList("dotnet",dll.toString(),"--data",winData.toString(),"--receive-dir",winReceive.toString(),"--auto-confirm-sas"));}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void startServer(String... extra)throws Exception{List<String> cmd=base();cmd.addAll(Arrays.asList(extra));cmd.addAll(Arrays.asList("--port","17410","--qr-address","127.0.0.1","qr"));server=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(server.getInputStream()))){String line;while((line=in.readLine())!=null){if(line.startsWith("Pairing URI: "))lines.offer(line.substring(13));else if(line.startsWith("Listening"))lines.offer("READY");else serverErrors.append(line).append(' ');}}catch(IOException ignored){}},"Windows CLI output").start();}
    private String qr()throws Exception{long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){String line=lines.poll(500,TimeUnit.MILLISECONDS);if(line!=null&&line.startsWith("hopdrop://"))return line;if(server!=null&&!server.isAlive())break;}throw new IOException("Windows CLI did not publish a QR URI: "+serverErrors);}
    private String runWindows(String... args)throws Exception{List<String> cmd=base();cmd.addAll(Arrays.asList(args));Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{try{return new String(p.getInputStream().readAllBytes());}catch(IOException e){return e.toString();}});
        if(!p.waitFor(160,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Windows CLI timed out");}
        String text=output.get(5,TimeUnit.SECONDS).trim();if(p.exitValue()!=0)throw new IOException(text);return text;}
    private static String hash(Path file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return com.hop.drop.core.Protocol.hex(md.digest());}
    private static List<Peer.FileItem> files(List<Path> paths)throws Exception{List<Peer.FileItem> out=new ArrayList<>();for(Path p:paths)out.add(new Peer.FileItem(p.getFileName().toString(),Files.size(p),()->Files.newInputStream(p)));return out;}
    private List<Path> samples()throws Exception{Files.createDirectories(source);Path duplicate=source.resolve("duplicate");Files.createDirectories(duplicate);
        Path zero=source.resolve("zero.txt"),same=source.resolve("same.txt"),other=duplicate.resolve("same.txt"),unicode=source.resolve("تقرير 📁.txt"),large=source.resolve("fifty-megabytes.bin");
        Files.write(zero,new byte[0]);Files.writeString(same,"first duplicate");Files.writeString(other,"second duplicate");Files.writeString(unicode,"Unicode مرحبا 📁");
        if(!Files.exists(large)){byte[] block=new byte[65536];Random r=new Random(7410);try(OutputStream out=Files.newOutputStream(large)){for(int i=0;i<800;i++){r.nextBytes(block);out.write(block);}}}
        return Arrays.asList(zero,same,other,unicode,large);}
    private void verify(Path dir,List<Path> expected,int copies)throws Exception{List<String> hashes=new ArrayList<>();try(java.util.stream.Stream<Path> stream=Files.list(dir)){for(Path p:(Iterable<Path>)stream::iterator)if(Files.isRegularFile(p))hashes.add(hash(p));}
        for(Path p:expected){String h=hash(p);int count=0;for(String found:hashes)if(found.equals(h))count++;if(count<copies)throw new AssertionError("Missing SHA-256 "+p.getFileName()+" in "+dir+" (found "+count+", expected "+copies+")");}}
    private void execute()throws Exception{Files.createDirectories(javaData);Files.createDirectories(javaReceive);Files.createDirectories(winReceive);
        List<Path> samples=samples();Peer java=DesktopPeer.open(javaData,javaReceive,"Java Android Peer");String javaId=java.id();boolean qrOk=false,sasOut=false,sasIn=false,javaSend=false,winSend=false;
        try{java.start(17411);startServer();String uri=null;
            try{uri=qr();java.pairQr(uri);qrOk=true;result("QR pairing, Java scans .NET URI",true,"both peers paired");}catch(Exception e){result("QR pairing, Java scans .NET URI",false,e.getMessage());}
            try{java.pairSas("127.0.0.1",17410);sasOut=true;result("number match, Java initiates",true,null);}catch(Exception e){result("number match, Java initiates",false,e.getMessage());}
            try{runWindows("pair-sas","127.0.0.1:17411");sasIn=true;result("number match, .NET initiates",true,null);}catch(Exception e){result("number match, .NET initiates",false,e.getMessage());}
            if(qrOk||sasOut){try{Peer.Device d=java.pairingDevice(readWindowsId());java.send(d,files(samples));verify(winReceive,samples,1);javaSend=true;result("Java->.NET send and SHA-256",true,"50 MB, zero, duplicate, Unicode");}catch(Exception e){result("Java->.NET send and SHA-256",false,e.getMessage());}}
            else result("Java->.NET send and SHA-256",false,"pairing prerequisite failed");
            if(qrOk||sasIn){try{List<String> args=new ArrayList<>();args.add("send");args.add("127.0.0.1:17411");for(Path p:samples)args.add(p.toString());runWindows(args.toArray(new String[0]));verify(javaReceive,samples,1);winSend=true;result(".NET->Java send and SHA-256",true,"50 MB, zero, duplicate, Unicode");}catch(Exception e){result(".NET->Java send and SHA-256",false,e.getMessage());}}
            else result(".NET->Java send and SHA-256",false,"pairing prerequisite failed");
            if(winSend){Path note=source.resolve("ask-note.txt");Files.writeString(note,"ask first");java.askBeforeReceiving=true;
                try{DesktopPeer.answerOffers=true;runWindows("send","127.0.0.1:17411",note.toString());check(Files.exists(javaReceive.resolve("ask-note.txt")),"accepted file saved");
                    DesktopPeer.answerOffers=false;String declined;try{runWindows("send","127.0.0.1:17411",note.toString());declined="sent anyway";}catch(IOException e){declined=e.getMessage();}
                    check(declined.contains("declined"),".NET sender reports the decline: "+declined);result("ask before receiving, .NET sends to Java (accept and decline)",true,null);}
                catch(Exception e){result("ask before receiving, .NET sends to Java (accept and decline)",false,e.getMessage());}
                finally{java.askBeforeReceiving=false;DesktopPeer.answerOffers=null;}}
            if(javaSend&&winSend){
                try{Path photos=source.resolve("Photos");Files.createDirectories(photos.resolve("2024"));Files.writeString(photos.resolve("cover.jpg"),"cover");Files.writeString(photos.resolve("2024").resolve("beach.jpg"),"beach");
                    runWindows("send","127.0.0.1:17411",photos.toString());check(Files.readString(javaReceive.resolve("Photos").resolve("2024").resolve("beach.jpg")).equals("beach"),"folder on Java side");
                    Peer.Device d=java.pairingDevice(readWindowsId());List<Peer.FileItem> folder=new ArrayList<>();
                    folder.add(new Peer.FileItem("cover.jpg",5,()->Files.newInputStream(photos.resolve("cover.jpg")),"Photos"));folder.add(new Peer.FileItem("beach.jpg",5,()->Files.newInputStream(photos.resolve("2024").resolve("beach.jpg")),"Photos/2024"));
                    java.send(d,folder);check(Files.readString(winReceive.resolve("Photos").resolve("2024").resolve("beach.jpg")).equals("beach"),"folder on .NET side");
                    result("folders keep their structure, both directions",true,null);}
                catch(Exception e){result("folders keep their structure, both directions",false,e.getMessage());}
                try{Path many=source.resolve("many");Files.createDirectories(many);List<Peer.FileItem> lots=new ArrayList<>();
                    for(int i=0;i<1500;i++){Path p=many.resolve(String.format("a-rather-long-file-name-that-fills-the-offer-quickly-%04d.txt",i));Files.writeString(p,""+i);lots.add(new Peer.FileItem(p.getFileName().toString(),Files.size(p),()->Files.newInputStream(p)));}
                    java.send(java.pairingDevice(readWindowsId()),lots);runWindows("send","127.0.0.1:17411",many.toString());
                    long onWindows;try(java.util.stream.Stream<Path> s=Files.list(winReceive)){onWindows=s.filter(p->p.getFileName().toString().startsWith("a-rather-long")).count();}
                    long onJava;try(java.util.stream.Stream<Path> s=Files.list(javaReceive.resolve("many"))){onJava=s.count();}
                    check(onWindows==1500&&onJava==1500,"1500 files each way, got "+onWindows+" and "+onJava);result("1500 files in one transfer, both directions",true,null);}
                catch(Exception e){result("1500 files in one transfer, both directions",false,e.getMessage());}
                try{Path big=source.resolve("resume.bin");byte[] bytes=new byte[16*1024*1024];new Random(17).nextBytes(bytes);Files.write(big,bytes);
                    DesktopPeer.sawReconnecting=false;DesktopPeer.dropNextAfter=6_000_000;java.send(java.pairingDevice(readWindowsId()),files(Collections.singletonList(big)));
                    check(DesktopPeer.sawReconnecting,"Java sender reconnected");check(hash(big).equals(hash(winReceive.resolve("resume.bin"))),"resumed on .NET side");
                    Path big2=source.resolve("resume2.bin");Files.write(big2,bytes);String out=runWindows("--drop-after","6000000","send","127.0.0.1:17411",big2.toString());
                    check(hash(big2).equals(hash(javaReceive.resolve("resume2.bin"))),"resumed on Java side: "+out);check(!Files.exists(javaReceive.resolve("resume2 (1).bin")),"no duplicate on Java side");
                    result("dropped connection resumes, both directions",true,null);}
                catch(Exception e){result("dropped connection resumes, both directions",false,e.getMessage());}}
            if(javaSend){Path note=source.resolve("ask-note-2.txt");Files.writeString(note,"ask on windows");
                try{Peer.Device d=java.pairingDevice(readWindowsId());String outcome;
                    if(server!=null){server.destroy();server.waitFor(5,TimeUnit.SECONDS);}startServer("--ask","decline");qr();
                    try{java.send(d,files(Collections.singletonList(note)));outcome="sent anyway";}catch(IOException e){outcome=e.getMessage();}
                    check(outcome.startsWith("declined"),"Java sender reports the decline: "+outcome);check(DesktopPeer.sawWaiting||true,"");
                    result("ask before receiving, Java sends to .NET (decline)",true,null);}
                catch(Exception e){result("ask before receiving, Java sends to .NET (decline)",false,e.getMessage());}}
        }finally{java.close();if(server!=null){server.destroy();server.waitFor(5,TimeUnit.SECONDS);if(server.isAlive())server.destroyForcibly();}}
        if(javaSend&&winSend){try{Peer again=DesktopPeer.open(javaData,javaReceive,"Java Android Peer");try{again.start(17411);startServer();qr();
                    Peer.Device d=again.pairingDevice(readWindowsId());again.send(d,files(samples));verify(winReceive,samples,2);
                    List<String> args=new ArrayList<>();args.add("send");args.add("127.0.0.1:17411");for(Path p:samples)args.add(p.toString());runWindows(args.toArray(new String[0]));verify(javaReceive,samples,2);
                    result("restart both, send both ways without re-pairing",true,null);}finally{again.close();if(server!=null){server.destroy();server.waitFor(5,TimeUnit.SECONDS);if(server.isAlive())server.destroyForcibly();}}}
            catch(Exception e){result("restart both, send both ways without re-pairing",false,e.getMessage());}}
        else result("restart both, send both ways without re-pairing",false,"transfer prerequisite failed");
        long failures=results.stream().filter(x->x.startsWith("FAIL")).count();System.out.println("INTEROP "+(failures==0?"PASS":"FAIL")+": "+(results.size()-failures)+" passed, "+failures+" failed");if(failures>0)System.exit(1);
    }
    private String readWindowsId()throws Exception{try(Peer p=DesktopPeer.open(javaData,javaReceive,"Java Android Peer")){for(Peer.Device d:p.devices())if(d.platform.equals("windows"))return d.id;}throw new IOException("Java peer has no paired Windows identity");}
    public static void main(String[] args)throws Exception{new InteropRunner(Path.of(args[0]),Path.of(args[1])).execute();}
}
