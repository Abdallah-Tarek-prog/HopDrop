package com.hop.drop.tests;

import com.hop.drop.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Desktop harness for the same Peer class shipped in the APK. */
public final class DesktopPeer {
    /** How tests answer "Ask before receiving": null = nobody to ask, true/false = accept/decline. */
    public static volatile Boolean answerOffers;
    /** Set when a sender reported that the receiver is asking its user. */
    public static volatile boolean sawWaiting;
    /** Set when a sender reported that it is reconnecting. */
    public static volatile boolean sawReconnecting;
    /** The next outgoing connection drops after this many bytes (then resets to -1); bytes written are counted in {@link #written}. */
    public static volatile long dropNextAfter=-1;
    public static final java.util.concurrent.atomic.AtomicLong written=new java.util.concurrent.atomic.AtomicLong();
    /** A socket whose writes stop after a limit, like a Wi-Fi link going away mid-transfer. */
    static final class DroppingSocket extends Socket {
        private final long limit;private long count;
        DroppingSocket(long limit){this.limit=limit;}
        @Override public OutputStream getOutputStream()throws IOException{OutputStream inner=super.getOutputStream();return new OutputStream(){
            public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
            public void write(byte[] b,int off,int len)throws IOException{if(limit>=0&&count+len>limit){close();throw new SocketException("Connection dropped");}inner.write(b,off,len);count+=len;written.addAndGet(len);}
            public void flush()throws IOException{inner.flush();}};}
    }
    public static Peer open(Path data,Path receive,String name) throws Exception {
        Files.createDirectories(data);Files.createDirectories(receive);KeyStore ks=KeyStore.getInstance("PKCS12");
        try(InputStream in=Files.newInputStream(data.resolve("identity.p12"))){ks.load(in,"android".toCharArray());}
        String alias=ks.aliases().nextElement();PrivateKey key=(PrivateKey)ks.getKey(alias,"android".toCharArray());X509Certificate cert=(X509Certificate)ks.getCertificate(alias);
        Peer.Book book=new Peer.Book(){
            private List<Peer.Device> read(){List<Peer.Device> out=new ArrayList<>();Path path=data.resolve("devices.json");if(!Files.exists(path))return out;
                try{List<?> list=(List<?>)Json.parseObject(Files.readString(path)).get("devices");for(Object item:list){Map<String,Object> m=(Map<String,Object>)item;Peer.Device d=new Peer.Device(Json.str(m,"id"),Json.str(m,"name"),Json.str(m,"platform"),(int)Json.num(m,"port"),null);
                    d.alias=(String)m.get("alias");d.trusted=Boolean.TRUE.equals(m.get("trusted"));d.pairedAt=Json.num(m,"pairedAt");d.lastSeen=Json.num(m,"lastSeen");for(Object a:(List<?>)m.get("addresses"))d.addresses.add((String)a);out.add(d);}}catch(Exception e){throw new IllegalStateException(e);}return out;}
            private void save(List<Peer.Device> list){List<Object> json=new ArrayList<>();for(Peer.Device d:list)json.add(Json.obj("id",d.id,"name",d.name,"platform",d.platform,"alias",d.alias,"trusted",d.trusted,"port",d.port,"pairedAt",d.pairedAt,"lastSeen",d.lastSeen,"addresses",d.addresses));
                try{Files.writeString(data.resolve("devices.json"),Json.string(Json.obj("devices",json)));}catch(Exception e){throw new IllegalStateException(e);}}
            public synchronized Peer.Device get(String id){for(Peer.Device d:read())if(d.id.equalsIgnoreCase(id))return d;return null;}
            public synchronized List<Peer.Device> all(){return read();}
            public synchronized void put(Peer.Device d){List<Peer.Device> list=read();list.removeIf(x->x.id.equalsIgnoreCase(d.id));list.add(d);save(list);}
            public synchronized void remove(String id){List<Peer.Device> list=read();list.removeIf(x->x.id.equalsIgnoreCase(id));save(list);}
        };
        Peer.Sockets sockets=new Peer.Sockets(){public Socket connect(String address,int port)throws IOException{long drop=dropNextAfter;dropNextAfter=-1;Socket s=new DroppingSocket(drop);s.connect(new InetSocketAddress(address,port),5000);s.setSoTimeout(Peer.idleTimeoutMs);return s;}
            public boolean localServerAddress(InetAddress address){return address.isLoopbackAddress();}};
        Peer.Storage storage=new Peer.Storage(){public long freeBytes(){return receive.toFile().getUsableSpace();}public String folder(){return receive.getFileName().toString();}
            public Output begin(String name)throws IOException{return begin(null,name);}
            public String uniqueFolder(String name){return FileNames.unique(name,n->Files.exists(receive.resolve(n)));}
            public Output begin(String folder,String name)throws IOException{Path dir=folder==null?receive:receive.resolve(folder);Files.createDirectories(dir);Path temp=Files.createTempFile(dir,".hopdrop-",".part");OutputStream out=Files.newOutputStream(temp);return new Output(){public OutputStream stream(){return out;}
                public String finish()throws IOException{out.close();String unique=FileNames.unique(name,n->Files.exists(dir.resolve(n)));Files.move(temp,dir.resolve(unique));return folder==null?unique:folder+"/"+unique;}
                public void abort(){try{out.close();Files.deleteIfExists(temp);}catch(IOException ignored){}}};}};
        Peer.Events events=new Peer.Events(){public boolean confirm(String peer,String code,boolean incoming){System.out.println("SAS "+(incoming?"incoming":"outgoing")+" "+peer+" "+code);return true;}
            public void progress(Peer.Progress p){if(p.waiting)sawWaiting=true;if(p.reconnecting)sawReconnecting=true;}
            public boolean offer(Peer.Offer o){Boolean answer=answerOffers;if(answer==null)return false;System.out.println("OFFER "+o.peer+" "+o.files);new Thread(()->o.answer(answer)).start();return true;}
            public void finished(Peer.Result r){System.out.println((r.error==null?"TRANSFER_OK":"TRANSFER_FAIL")+" "+(r.incoming?"RECEIVE":"SEND")+" "+r.peer+" "+r.files+" "+r.folder+" "+r.error);}
            public void paired(Peer.Device d){System.out.println("PAIRED "+d.id+" "+d.name);}};
        return new Peer(key,cert,name,book,sockets,storage,events);
    }
    public static void main(String[] args)throws Exception{if(args.length<5)throw new IllegalArgumentException("data receive name port command [args]");Path data=Path.of(args[0]),receive=Path.of(args[1]);String name=args[2];int port=Integer.parseInt(args[3]);
        try(Peer p=open(data,receive,name)){String command=args[4];switch(command){case "serve":p.start(port);System.out.println("LISTENING "+p.id()+" "+port);System.out.flush();Thread.sleep(Long.MAX_VALUE);break;
            case "qr":p.start(port);System.out.println("PAIRING_URI "+p.issueQr(Collections.singletonList("127.0.0.1"),port));System.out.flush();Thread.sleep(Long.MAX_VALUE);break;
            case "pair-qr":p.pairQr(args[5]);System.out.println("PAIR_QR_OK");break;
            case "pair-sas":p.pairSas(args[5],Integer.parseInt(args[6]));System.out.println("PAIR_SAS_OK");break;
            case "send":Peer.Device d=p.pairingDevice(args[5]);List<Peer.FileItem> files=new ArrayList<>();for(int i=6;i<args.length;i++){Path file=Path.of(args[i]);files.add(new Peer.FileItem(file.getFileName().toString(),Files.size(file),()->Files.newInputStream(file)));}p.send(d,files);System.out.println("SEND_OK");break;
            case "devices":for(Peer.Device dev:p.devices())System.out.println("DEVICE "+dev.id+" "+dev.name);break;
            case "id":System.out.println("ID "+p.id());break;
            default:throw new IllegalArgumentException("Unknown command "+command);}}}
}
