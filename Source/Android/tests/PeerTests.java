package com.hop.drop.tests;

import com.hop.drop.core.Peer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class PeerTests {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static String hash(Path p)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)m.update(b,0,n);}return com.hop.drop.core.Protocol.hex(m.digest());}
    private static List<Peer.FileItem> items(Path... files)throws Exception{List<Peer.FileItem> list=new ArrayList<>();for(Path f:files)list.add(new Peer.FileItem(f.getFileName().toString(),Files.size(f),()->Files.newInputStream(f)));return list;}
    public static void main(String[] args)throws Exception{Path root=Path.of(args[0]);Path a=root.resolve("a"),b=root.resolve("b"),ar=a.resolve("received"),br=b.resolve("received");Files.createDirectories(ar);Files.createDirectories(br);
        Path src=root.resolve("files"),dup=root.resolve("other");Files.createDirectories(src);Files.createDirectories(dup);
        Path zero=src.resolve("empty.txt"),unicode=src.resolve("تقرير 📁.txt"),first=src.resolve("same.txt"),second=dup.resolve("same.txt"),large=src.resolve("large.bin");
        Files.write(zero,new byte[0]);Files.writeString(unicode,"مرحبا HopDrop");Files.writeString(first,"first");Files.writeString(second,"second");
        if(!Files.exists(large)){byte[] bytes=new byte[50*1024*1024];new Random(47).nextBytes(bytes);Files.write(large,bytes);}
        String aid,bid;try(Peer pa=DesktopPeer.open(a,ar,"Java A");Peer pb=DesktopPeer.open(b,br,"Java B")){
            aid=pa.id();bid=pb.id();pa.start(17610);pb.start(17611);
            try{pa.send(new Peer.Device(bid,"Java B","android",17611,"127.0.0.1"),items(zero));throw new AssertionError("Unpaired offer accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("not_paired"),"not-paired rejection");}
            String uri=pb.issueQr(Collections.singletonList("127.0.0.1"),17611);com.hop.drop.core.PairUri q=com.hop.drop.core.PairUri.parse(uri);
            try{pa.pairQr(new com.hop.drop.core.PairUri("00".repeat(32),q.name,q.platform,q.port,q.addresses,q.token).toString());throw new AssertionError("Identity mismatch accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("identity_mismatch"),"QR identity pinning");}
            try{pa.pairQr(new com.hop.drop.core.PairUri(q.id,q.name,q.platform,q.port,q.addresses,"AAECAwQFBgcICQoLDA0ODw").toString());throw new AssertionError("Bad token accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("bad_token"),"QR bad token");}
            pa.pairQr(uri);check(pa.pairingDevice(bid)!=null&&pb.pairingDevice(aid)!=null,"QR pairing both stores");
            try{pa.pairQr(uri);throw new AssertionError("Used token accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("bad_token"),"QR one-time token");}
            System.out.println("PASS Java QR pairing, pinning, token rules, not-paired rejection");
            pa.pairSas("127.0.0.1",17611);pb.pairSas("127.0.0.1",17610);System.out.println("PASS Java SAS each direction");
            pa.pairQr(pb.issueQr(Collections.singletonList("127.0.0.1"),17611));
            pa.send(pa.pairingDevice(bid),items(zero,first,second,unicode,large));
            for(Path p:new Path[]{zero,first,second,unicode,large}){Path dest=br.resolve(p.equals(second)?"same (1).txt":p.getFileName().toString());check(Files.exists(dest)&&hash(p).equals(hash(dest)),"Java A->B checksum "+p);}
            pb.send(pb.pairingDevice(aid),items(unicode,zero));check(hash(unicode).equals(hash(ar.resolve(unicode.getFileName()))),"Java B->A checksum");System.out.println("PASS Java transfers both directions, 50 MB, zero, duplicate, Unicode");}
        try(Peer pa=DesktopPeer.open(a,ar,"Java A");Peer pb=DesktopPeer.open(b,br,"Java B")){
            pa.start(17610);pb.start(17611);check(pa.pairingDevice(bid)!=null&&pb.pairingDevice(aid)!=null,"pairing persisted");
            pa.send(pa.pairingDevice(bid),items(first));check(hash(first).equals(hash(br.resolve("same (2).txt"))),"restart transfer");System.out.println("PASS Java restart without re-pairing");}
        try(Peer pa=DesktopPeer.open(a,ar,"Java A");Peer pb=DesktopPeer.open(b,br,"Java B")){pa.start(17610);pb.start(17611);
            Path note=src.resolve("note.txt");Files.writeString(note,"hello");pb.askBeforeReceiving=true;
            DesktopPeer.answerOffers=false;DesktopPeer.sawWaiting=false;
            try{pa.send(pa.pairingDevice(pb.id()),items(note));throw new AssertionError("Declined offer was sent");}catch(java.io.IOException expected){check(expected.getMessage().startsWith("declined"),"declined code: "+expected.getMessage());}
            check(DesktopPeer.sawWaiting,"sender reports that the receiver is asking");check(!Files.exists(br.resolve("note.txt")),"declined file not saved");
            DesktopPeer.answerOffers=true;pa.send(pa.pairingDevice(pb.id()),items(note));check(Files.exists(br.resolve("note.txt")),"accepted file saved");
            pb.setTrusted(pa.id(),true);DesktopPeer.answerOffers=false;pa.send(pa.pairingDevice(pb.id()),items(note));check(Files.exists(br.resolve("note (1).txt")),"trusted device isn't asked");
            pa.pairQr(pb.issueQr(Collections.singletonList("127.0.0.1"),17611));check(pb.pairingDevice(pa.id()).trusted,"re-pairing keeps trust");
            DesktopPeer.answerOffers=null;System.out.println("PASS Java ask before receiving: decline, accept, trusted devices skip, trust survives re-pairing");}
        try(Peer pa=DesktopPeer.open(a,ar,"Java A");Peer pb=DesktopPeer.open(b,br,"Java B")){pa.start(17610);pb.start(17611);Peer.Device toB=pa.pairingDevice(pb.id());
            // Folders keep their structure; a second send of the same folder doesn't merge into the first.
            Path photos=src.resolve("Photos");Files.createDirectories(photos.resolve("2024"));Path cover=photos.resolve("cover.jpg"),beach=photos.resolve("2024").resolve("beach.jpg");Files.writeString(cover,"cover");Files.writeString(beach,"beach");
            List<Peer.FileItem> folder=new ArrayList<>();folder.add(new Peer.FileItem("cover.jpg",5,()->Files.newInputStream(cover),"Photos"));folder.add(new Peer.FileItem("beach.jpg",5,()->Files.newInputStream(beach),"Photos/2024"));folder.add(new Peer.FileItem("evil.txt",5,()->Files.newInputStream(cover),"../../outside"));
            List<String> savedAs=pa.send(toB,folder);check(savedAs.contains("Photos/2024/beach.jpg"),"folder savedAs "+savedAs);
            check(Files.readString(br.resolve("Photos").resolve("2024").resolve("beach.jpg")).equals("beach"),"folder structure kept");check(Files.exists(br.resolve("_").resolve("_").resolve("outside").resolve("evil.txt")),"unsafe folder names cleaned");
            pa.send(toB,folder);check(Files.exists(br.resolve("Photos (1)").resolve("2024").resolve("beach.jpg")),"second folder send doesn't merge");
            System.out.println("PASS Java folders keep their structure, unsafe names cleaned, no merging");
            // A big selection goes in one transfer (the offer is split over several messages).
            Path many=root.resolve("many");Files.createDirectories(many);List<Peer.FileItem> lots=new ArrayList<>();
            for(int i=0;i<1500;i++){Path p=many.resolve(String.format("a-rather-long-file-name-that-fills-the-offer-quickly-%04d.txt",i));Files.writeString(p,""+i);lots.add(new Peer.FileItem(p.getFileName().toString(),Files.size(p),()->Files.newInputStream(p)));}
            check(pa.send(toB,lots).size()==1500,"1500 files sent");long count;try(java.util.stream.Stream<Path> listed=Files.list(br)){count=listed.filter(p->p.getFileName().toString().startsWith("a-rather-long")).count();}check(count==1500,"1500 files received, got "+count);
            System.out.println("PASS Java 1500 files in one transfer");
            // A dropped connection resumes: nothing resent, nothing duplicated.
            Path big=root.resolve("big.bin");byte[] bytes=new byte[16*1024*1024];new Random(9).nextBytes(bytes);Files.write(big,bytes);Path note=src.resolve("twice.txt");Files.writeString(note,"same name twice");
            List<Peer.FileItem> drop=items(note);drop.addAll(items(big));drop.addAll(items(note));DesktopPeer.sawReconnecting=false;DesktopPeer.written.set(0);DesktopPeer.dropNextAfter=6_000_000;
            List<String> resumed=pa.send(toB,drop);check(DesktopPeer.sawReconnecting,"sender reconnected");check(resumed.size()==3,"three files confirmed "+resumed);
            check(hash(big).equals(hash(br.resolve("big.bin"))),"resumed file intact");check(Files.exists(br.resolve("twice.txt"))&&Files.exists(br.resolve("twice (1).txt"))&&!Files.exists(br.resolve("twice (2).txt")),"no duplicates");
            check(DesktopPeer.written.get()<bytes.length*13L/10,"resumed instead of resending: "+DesktopPeer.written.get());
            System.out.println("PASS Java dropped connection resumes without resending or duplicates");}
        // Regression: a single file that takes longer than the idle timeout to upload must not fail on the sender,
        // whose reader hears nothing until file_ok (phone->laptop uploads over ~30 s used to fail).
        Peer.idleTimeoutMs=1500;
        try(Peer pa=DesktopPeer.open(a,ar,"Java A");Peer pb=DesktopPeer.open(b,br,"Java B")){pa.start(17610);pb.start(17611);String slowTarget=pb.id();
            byte[] chunk=new byte[65536];new Random(3).nextBytes(chunk);int chunks=40;
            Peer.FileItem slow=new Peer.FileItem("slow.bin",(long)chunk.length*chunks,()->new java.io.InputStream(){int sent=0,pos=0;
                public int read(){byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
                public int read(byte[] buf,int off,int len){if(sent>=chunks)return -1;if(pos==0)try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    int n=Math.min(len,chunk.length-pos);System.arraycopy(chunk,pos,buf,off,n);pos+=n;if(pos==chunk.length){pos=0;sent++;}return n;}});
            pa.send(pa.pairingDevice(slowTarget),Collections.singletonList(slow));check(Files.size(br.resolve("slow.bin"))==(long)chunk.length*chunks,"slow upload size");
            System.out.println("PASS Java slow upload longer than the idle timeout");}
        finally{Peer.idleTimeoutMs=30000;}
    }
}
