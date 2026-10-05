package com.hop.drop.core;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Mutual-TLS peer with platform adapters for networking, persistence and files. */
public final class Peer implements AutoCloseable {
    public static final class Device {
        public String id,name,platform,alias; public int port; public long pairedAt,lastSeen;
        /** The user trusts this device: its files arrive without asking, even with "Ask before receiving" on. */
        public boolean trusted;
        public final List<String> addresses=new ArrayList<>();
        public Device(String id,String name,String platform,int port,String address) { this.id=id;this.name=name;this.platform=platform;this.port=port;pairedAt=lastSeen=System.currentTimeMillis();if(address!=null)addresses.add(address); }
        public String label(){return alias==null||alias.isEmpty()?name:alias;}
    }
    /** Idle read timeout for connections (spec 3.6: 30 s). Tests lower it to exercise slow transfers quickly. */
    public static volatile int idleTimeoutMs=30000;
    public interface Book { Device get(String id); List<Device> all(); void put(Device d); void remove(String id); }
    public interface Sockets { Socket connect(String address,int port) throws IOException; boolean localServerAddress(InetAddress address); }
    public interface Storage { long freeBytes(); String folder(); Output begin(String name) throws IOException;
        /** Saves into a folder inside the receive folder ("Photos/2024"); null means the receive folder itself. {@link Output#finish} then returns "Photos/2024/name". */
        default Output begin(String folder,String name) throws IOException { return begin(name); }
        /** A name for a received top-level folder that isn't taken yet: "Photos", else "Photos (1)". */
        default String uniqueFolder(String name) { return name; }
        interface Output { OutputStream stream() throws IOException; String finish() throws IOException; void abort(); }
    }
    /** A live snapshot of one transfer. {@code total}/{@code secondsLeft} are -1 when unknown. */
    public static final class Progress {
        public final String transferId,peer,file; public final boolean incoming; public final int index,count; public final long done,total,secondsLeft; public final double speed;
        /** Sending: the receiver is asking its user whether to take the files. */
        public final boolean waiting;
        public Progress(String transferId,boolean incoming,String peer,String file,int index,int count,long done,long total,double speed,long secondsLeft){
            this(transferId,incoming,peer,file,index,count,done,total,speed,secondsLeft,false);}
        /** The connection dropped: the sender is reconnecting (or, receiving, the phone waits for it to come back). */
        public final boolean reconnecting;
        public Progress(String transferId,boolean incoming,String peer,String file,int index,int count,long done,long total,double speed,long secondsLeft,boolean waiting){
            this(transferId,incoming,peer,file,index,count,done,total,speed,secondsLeft,waiting,false);}
        public Progress(String transferId,boolean incoming,String peer,String file,int index,int count,long done,long total,double speed,long secondsLeft,boolean waiting,boolean reconnecting){
            this.transferId=transferId;this.incoming=incoming;this.peer=peer;this.file=file;this.index=index;this.count=count;this.done=done;this.total=total;this.speed=speed;this.secondsLeft=secondsLeft;this.waiting=waiting;this.reconnecting=reconnecting;}
        public int percent(){return total>0?(int)Math.min(100,done*100/total):-1;}
    }
    /** How a transfer ended. {@code error} is null on success; {@code files} are the files that were saved. */
    public static final class Result {
        public final String transferId,peer,folder,error; public final boolean incoming; public final List<String> files; public final int offered; public final long bytes,millis;
        public Result(String transferId,boolean incoming,String peer,List<String> files,int offered,String folder,String error,long bytes,long millis){
            this.transferId=transferId;this.incoming=incoming;this.peer=peer;this.files=files;this.offered=offered;this.folder=folder;this.error=error;this.bytes=bytes;this.millis=millis;}
    }
    /** Files offered by a device that isn't trusted while "Ask before receiving" is on. */
    public static final class Offer {
        public final String transferId,peer; public final List<String> files; public final long total,deadline;
        private final CountDownLatch done=new CountDownLatch(1); private volatile boolean accepted;
        Offer(String transferId,String peer,List<String> files,long total,long deadline){this.transferId=transferId;this.peer=peer;this.files=files;this.total=total;this.deadline=deadline;}
        /** The first answer counts; later ones (and the automatic decline when time runs out) are ignored. */
        public synchronized void answer(boolean accept){if(done.getCount()==0)return;accepted=accept;done.countDown();}
    }
    public interface Events { boolean confirm(String name,String code,boolean incoming) throws InterruptedException;
        void progress(Progress progress);
        void finished(Result result); void paired(Device d);
        default void pairingEnded(String reason) { }
        /** Show the question; answer through {@link Offer#answer}. Return false when nobody can be asked (the files are accepted). */
        default boolean offer(Offer offer) { return false; }
        /** The question is over (answered, timed out, or the sender gave up), so the prompt can close. */
        default void offerClosed(Offer offer) { }
    }
    public interface Opener { InputStream open() throws IOException; }
    public static final class FileItem { public final String name; public final long size; public final Opener opener;
        /** Where the file sits inside a sent folder ("Photos/2024"), or null for a loose file. */
        public final String folder;
        public FileItem(String name,long size,Opener opener){this(name,size,opener,null);}
        public FileItem(String name,long size,Opener opener,String folder){this.name=name;this.size=size;this.opener=opener;this.folder=folder;} }
    private final SSLContext tls; private final Book book; private final Sockets sockets; private final Storage storage; private final Events events;
    private final ExecutorService workers=Executors.newCachedThreadPool(); private final Semaphore sasGate=new Semaphore(1);
    private final List<Long> attempts=new ArrayList<>(); private final String id; private volatile String name,qrToken;
    private volatile long qrExpires; private volatile ServerSocket listener; private volatile boolean running; private final java.util.concurrent.atomic.AtomicInteger receiving=new java.util.concurrent.atomic.AtomicInteger();
    private final Set<SSLSocket> incomingSockets=Collections.newSetFromMap(new ConcurrentHashMap<SSLSocket,Boolean>());
    private final Set<SSLSocket> cancelledSockets=Collections.newSetFromMap(new ConcurrentHashMap<SSLSocket,Boolean>());
    private final Set<SSLSocket> outgoingSockets=Collections.newSetFromMap(new ConcurrentHashMap<SSLSocket,Boolean>());
    private volatile boolean outgoingCancelled;
    /** More simultaneous connections than this are dropped at once, so a flood from the network can't exhaust the phone. */
    private static final int MAX_CONNECTIONS=32;
    private final java.util.concurrent.atomic.AtomicInteger connections=new java.util.concurrent.atomic.AtomicInteger();
    /** Files from devices that aren't trusted wait for the user's answer. */
    public volatile boolean askBeforeReceiving;
    public Peer(PrivateKey key,X509Certificate cert,String name,Book book,Sockets sockets,Storage storage,Events events) throws Exception {
        this.id=Protocol.id(cert);this.name=name;this.book=book;this.sockets=sockets;this.storage=storage;this.events=events;
        X509ExtendedKeyManager identity = new X509ExtendedKeyManager() {
            private final String alias = "identity";
            private final X509Certificate[] chain = {cert};

            private String choose(String keyType) {
                return keyType != null && keyType.toUpperCase(Locale.ROOT).startsWith("EC") ? alias : null;
            }

            @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
                return choose(keyType) == null ? null : new String[]{alias};
            }

            @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                for (String type : keyTypes) {
                    if (choose(type) != null) return alias;
                }
                return null;
            }

            @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
                return getClientAliases(keyType, issuers);
            }

            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return choose(keyType);
            }

            @Override public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
                return chooseClientAlias(keyTypes, issuers, null);
            }

            @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return choose(keyType);
            }

            @Override public X509Certificate[] getCertificateChain(String requestedAlias) {
                return alias.equals(requestedAlias) ? chain.clone() : null;
            }

            @Override public PrivateKey getPrivateKey(String requestedAlias) {
                return alias.equals(requestedAlias) ? key : null;
            }
        };
        X509TrustManager trust=new X509TrustManager(){public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{if(c==null||c.length==0)throw new CertificateException("Missing certificate");}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{if(c==null||c.length==0)throw new CertificateException("Missing certificate");}
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}};
        tls=SSLContext.getInstance("TLS");tls.init(new KeyManager[]{identity},new TrustManager[]{trust},null);
    }
    public String id(){return id;} public String name(){return name;}
    public Device pairingDevice(String id){return book.get(id);}
    public void setTrusted(String id,boolean trusted){Device d=book.get(id);if(d==null)return;d.trusted=trusted;book.put(d);}
    public List<Device> devices(){return book.all();}
    public boolean isReceiving(){return receiving.get()>0;}
    public void cancelIncoming(){for(SSLSocket s:incomingSockets)try{cancelledSockets.add(s);Protocol.send(s.getOutputStream(),"type","cancel");s.close();}catch(Exception ignored){}}
    /** Stops the outgoing transfer at once: closing its connection unblocks a write that a thread interrupt can't. */
    public void cancelOutgoing(){outgoingCancelled=true;for(SSLSocket s:outgoingSockets)try{s.close();}catch(Exception ignored){}}
    public void setName(String n){if(n.length()<1||n.length()>40)throw new IllegalArgumentException("Name must have 1–40 characters");name=n;}
    public synchronized String issueQr(List<String> addresses,int port){qrToken=PairUri.token();qrExpires=System.currentTimeMillis()+300000;return new PairUri(id,name,"android",port,addresses,qrToken).toString();}
    public synchronized void invalidateQr(){qrToken=null;}
    private synchronized String consumeQr(String token){if(qrToken==null)return "bad_token";if(System.currentTimeMillis()>=qrExpires){qrToken=null;return "token_expired";}
        if(!Protocol.equal(qrToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII),token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return "bad_token";qrToken=null;return "pair_ok";}
    public void start(int port)throws IOException {if(running)return;SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(port);
        server.setNeedClientAuth(true);server.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});listener=server;running=true;
        workers.execute(()->{while(running)try{Socket s=server.accept();if(!sockets.localServerAddress(s.getLocalAddress())){s.close();continue;}
            if(connections.incrementAndGet()>MAX_CONNECTIONS){connections.decrementAndGet();s.close();continue;}
            workers.execute(()->{try{handle(s);}finally{connections.decrementAndGet();}});}catch(IOException e){if(running)try{Thread.sleep(200);}catch(InterruptedException stop){return;}}});}
    private static final class Link implements AutoCloseable {final SSLSocket s;final InputStream in;final OutputStream out;final String id,name,platform,address;final Set<String> features;
        Link(SSLSocket s,String id,String name,String platform,String address,Set<String> features)throws IOException{this.s=s;in=s.getInputStream();out=s.getOutputStream();this.id=id;this.name=name;this.platform=platform;this.address=address;this.features=features;}
        public void close()throws IOException{s.close();}}
    private Link connect(String address,int port,String expected)throws Exception {Socket raw=sockets.connect(address,port);try{
        SSLSocket s=(SSLSocket)tls.getSocketFactory().createSocket(raw,address,port,true);s.setUseClientMode(true);s.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});s.setSoTimeout(idleTimeoutMs);s.startHandshake();
        String peerId=Protocol.id((X509Certificate)s.getSession().getPeerCertificates()[0]);Protocol.hello(s.getOutputStream(),id,name,"android",book.get(peerId)!=null);
        Map<String,Object> h=Protocol.checkHello(Protocol.message(s.getInputStream()),peerId);if(expected!=null&&!expected.equalsIgnoreCase(peerId))throw new IOException("identity_mismatch: This isn't the device you paired with");
        return new Link(s,peerId,Json.str(h,"name"),Json.str(h,"platform"),address,Protocol.features(h));}catch(Exception e){raw.close();throw e;}}
    private Link connectAny(List<String> addresses,int port,String id)throws Exception{
        if(addresses.isEmpty())throw new IOException("No saved address for device");
        CompletionService<Link> completed=new ExecutorCompletionService<>(workers);List<Future<Link>> jobs=new ArrayList<>();java.util.concurrent.atomic.AtomicBoolean won=new java.util.concurrent.atomic.AtomicBoolean();
        for(String address:new LinkedHashSet<>(addresses))jobs.add(completed.submit(()->{Link l=connect(address,port,id);if(won.compareAndSet(false,true))return l;l.close();throw new IOException("Another address connected first");}));
        Exception last=null;try{for(int i=0;i<jobs.size();i++)try{return completed.take().get();}catch(ExecutionException e){last=e.getCause() instanceof Exception?(Exception)e.getCause():new IOException(e.getCause());}}
        finally{for(Future<Link> job:jobs)if(!job.isDone())job.cancel(true);}
        throw last==null?new IOException("No reachable address"):last;
    }
    private void remember(Link l,int port){Device d=book.get(l.id);if(d==null)d=new Device(l.id,l.name,l.platform,port,l.address);d.name=l.name;d.platform=l.platform;d.port=port;d.lastSeen=System.currentTimeMillis();d.addresses.remove(l.address);d.addresses.add(0,l.address);book.put(d);events.paired(d);}
    public Device pairQr(String uri)throws Exception{PairUri q=PairUri.parse(uri);try(Link l=connectAny(q.addresses,q.port,q.id)){Protocol.send(l.out,"type","pair_qr","token",q.token);Protocol.expect(Protocol.message(l.in),"pair_ok");remember(l,q.port);return book.get(l.id);}}
    public Device pairSas(String address,int port)throws Exception{try(Link l=connect(address,port,null)){Protocol.send(l.out,"type","pair_sas");Map<String,Object> c=Protocol.message(l.in);Protocol.expect(c,"sas_commit");
        byte[] ni=Protocol.random(32);Protocol.send(l.out,"type","sas_nonce","n",Protocol.hex(ni));Map<String,Object> reveal=Protocol.message(l.in);Protocol.expect(reveal,"sas_reveal");
        byte[] nr=Protocol.unhex(Json.str(reveal,"n"));if(nr.length!=32||!Sas.commit(Protocol.unhex(l.id),Protocol.unhex(id),nr).equalsIgnoreCase(Json.str(c,"c")))throw new IOException("protocol_error: SAS commitment mismatch");
        String code=Sas.code(Protocol.unhex(id),Protocol.unhex(l.id),ni,nr);l.s.setSoTimeout(130000);
        Future<Map<String,Object>> remote=readPairingAnswer(l.in);
        boolean ok=events.confirm(l.name,code.substring(0,3)+" "+code.substring(3),false);
        Protocol.send(l.out,"type","sas_confirm","ok",ok);
        Map<String,Object> answer=remote.get();Protocol.expect(answer,"sas_confirm");
        if(!ok||!Json.bool(answer,"ok"))throw new IOException("user_declined: Pairing cancelled");
        remember(l,port);return book.get(l.id);}}
    public void unpair(Device d){try(Link l=connectAny(d.addresses,d.port,d.id)){Protocol.send(l.out,"type","unpair");Protocol.expect(Protocol.message(l.in),"ok");}catch(Exception ignored){}book.remove(d.id);}
    /** A just-paired device, keeping the alias and trust of an earlier pairing with it. */
    private Device fresh(String peerId,String peerName,String platform,String address){Device old=book.get(peerId);Device d=new Device(peerId,peerName,platform,old==null?7410:old.port,address);
        if(old!=null){d.alias=old.alias;d.trusted=old.trusted;d.pairedAt=old.pairedAt;}return d;}
    private void error(SSLSocket s,String code,String msg){if(msg!=null&&msg.startsWith(code+": "))msg=msg.substring(code.length()+2);try{Protocol.send(s.getOutputStream(),"type","error","code",code,"message",msg);}catch(Exception ignored){}}
    private void handle(Socket raw){try(SSLSocket s=(SSLSocket)raw){try{s.setSoTimeout(idleTimeoutMs);s.startHandshake();String peerId=Protocol.id((X509Certificate)s.getSession().getPeerCertificates()[0]);
        Map<String,Object> hello=Protocol.checkHello(Protocol.message(s.getInputStream()),peerId);boolean paired=book.get(peerId)!=null;Protocol.hello(s.getOutputStream(),id,name,"android",paired);
        String peerName=Json.str(hello,"name"),platform=Json.str(hello,"platform"),address=raw.getInetAddress().getHostAddress();Set<String> features=Protocol.features(hello);Map<String,Object> req=Protocol.message(s.getInputStream());String type=Protocol.type(req);
        if(!paired&&(type.equals("offer")||type.equals("ping")||type.equals("unpair"))){error(s,"not_paired","Pair this device first");return;}
        switch(type){case "ping":Protocol.send(s.getOutputStream(),"type","pong");break;case "unpair":book.remove(peerId);Protocol.send(s.getOutputStream(),"type","ok");break;
            case "pair_qr":String result=consumeQr(Json.str(req,"token"));if(!result.equals("pair_ok")){error(s,result,"QR code expired or invalid");return;}
                Device q=fresh(peerId,peerName,platform,address);book.put(q);Protocol.send(s.getOutputStream(),"type","pair_ok");events.paired(q);break;
            case "pair_sas":respondSas(s,peerId,peerName,platform,address);break;case "offer":receiving.incrementAndGet();incomingSockets.add(s);try{Device known=book.get(peerId);receive(s,req,peerId,known==null?peerName:known.label(),known!=null&&known.trusted,features);}finally{incomingSockets.remove(s);cancelledSockets.remove(s);receiving.decrementAndGet();}break;default:error(s,"protocol_error","Unknown request");}}
        catch(Exception e){String msg=e.getMessage()==null?"Protocol error":e.getMessage();String code=msg.startsWith("identity_mismatch")?"identity_mismatch":msg.startsWith("unsupported_version")?"unsupported_version":msg.startsWith("user_declined")?"user_declined":msg.startsWith("declined")?"declined":msg.startsWith("cancelled")?"cancelled":msg.startsWith("timeout")?"timeout":e instanceof IllegalArgumentException||msg.startsWith("protocol_error")?"protocol_error":"io_error";error(s,code,msg);}}
        catch(Exception ignored){/* TLS handshake failed before protocol messages. */}}
    private void respondSas(SSLSocket s,String peerId,String peerName,String platform,String address)throws Exception{
        synchronized(attempts){attempts.removeIf(t->t<System.currentTimeMillis()-600000);if(attempts.size()>=5){error(s,"busy","Too many pairing attempts");return;}attempts.add(System.currentTimeMillis());}
        if(!sasGate.tryAcquire()){error(s,"busy","Pairing already in progress");return;}try{byte[] nr=Protocol.random(32);Protocol.send(s.getOutputStream(),"type","sas_commit","c",Sas.commit(Protocol.unhex(id),Protocol.unhex(peerId),nr));
        Map<String,Object> nonce=Protocol.message(s.getInputStream());Protocol.expect(nonce,"sas_nonce");byte[] ni=Protocol.unhex(Json.str(nonce,"n"));if(ni.length!=32)throw new IOException("protocol_error: Invalid nonce");
        Protocol.send(s.getOutputStream(),"type","sas_reveal","n",Protocol.hex(nr));String code=Sas.code(Protocol.unhex(peerId),Protocol.unhex(id),ni,nr);s.setSoTimeout(130000);
        Future<Map<String,Object>> remote=readPairingAnswer(s.getInputStream());
        boolean ok=events.confirm(peerName,code.substring(0,3)+" "+code.substring(3),true);
        Protocol.send(s.getOutputStream(),"type","sas_confirm","ok",ok);
        Map<String,Object> answer=remote.get();Protocol.expect(answer,"sas_confirm");
        if(ok&&Json.bool(answer,"ok")){Device d=fresh(peerId,peerName,platform,address);book.put(d);events.paired(d);}else error(s,"user_declined","Pairing cancelled");}
        finally{sasGate.release();}}
    private Future<Map<String,Object>> readPairingAnswer(InputStream input) {
        return workers.submit(() -> {
            try {
                Map<String,Object> answer = Protocol.message(input);
                if (!"sas_confirm".equals(Json.str(answer, "type")) || !Json.bool(answer, "ok")) {
                    events.pairingEnded("Pairing was cancelled.");
                }
                return answer;
            } catch (Exception error) {
                events.pairingEnded(error instanceof SocketTimeoutException
                        ? "Pairing timed out." : "Pairing was cancelled.");
                throw error;
            }
        });
    }
    /** One offered file: its place in this offer, in the whole transfer (differs once a resumed offer leaves out saved files), and its folder. */
    private static final class Offered {
        final int index,key; final String name,folder; final long size;
        Offered(int index,int key,String name,long size,String folder){this.index=index;this.key=key;this.name=name;this.size=size;this.folder=folder;}
        Offered at(int newIndex){return new Offered(newIndex,key,name,size,folder);}
        boolean same(Offered o){return key==o.key&&name.equals(o.name)&&size==o.size&&Objects.equals(folder,o.folder);}
        Map<String,Object> entry(){Map<String,Object> e=Json.obj("i",index,"name",name,"size",size);if(folder!=null)e.put("path",folder);if(key!=index)e.put("k",key);return e;}
        int bytes(){return Json.string(entry()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;}
    }
    /** A receiver takes at most this many files in one transfer; bigger selections go in several. */
    private static final int MAX_FILES=10000;
    /** File entries in one offer message stay under this many bytes (control messages are capped at 64 KiB). */
    private static final int OFFER_BUDGET=56000;
    /** After a dropped connection the sender tries again this many times, waiting 2, 4 and 8 s. */
    private static final int RECONNECTS=3;
    /** How long a receiver keeps a half-received transfer for the sender to come back. Tests shorten it. */
    public static volatile long keepInterruptedMs=120000;
    private final Map<String,Interrupted> interrupted=new ConcurrentHashMap<>();
    private final Object folderLock=new Object();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"HopDrop timers");t.setDaemon(true);return t;});

    /** What a receiver keeps of an incoming transfer whose connection dropped, so the sender can continue it. */
    private static final class Interrupted {
        final String transferId,peerName,folder;final int offered;final boolean trusted;final long startedNanos;
        final List<Offered> completedFiles;final List<String> completedNames;final Map<String,String> folders;long bytes;
        Offered partial;Storage.Output partialOutput;MessageDigest partialHash;long partialWritten;ScheduledFuture<?> expiry;
        Interrupted(String transferId,String peerName,String folder,int offered,boolean trusted,long startedNanos,List<Offered> completedFiles,List<String> completedNames,Map<String,String> folders,long bytes){
            this.transferId=transferId;this.peerName=peerName;this.folder=folder;this.offered=offered;this.trusted=trusted;this.startedNanos=startedNanos;
            this.completedFiles=completedFiles;this.completedNames=completedNames;this.folders=folders;this.bytes=bytes;}
        void dropPartial(){if(partialOutput!=null)partialOutput.abort();partialOutput=null;partialHash=null;partial=null;partialWritten=0;}
    }
    /** What a sender knows about its transfer across reconnects. */
    private static final class Outgoing {
        final String[] savedAs;long done;String folder="";
        Outgoing(int count){savedAs=new String[count];}
        List<String> saved(){List<String> out=new ArrayList<>();for(String s:savedAs)if(s!=null)out.add(s);return out;}
    }
    /** A folder path made safe to recreate: "/"-separated, every part a valid file name, no "..", at most 32 levels. Null for none. */
    public static String cleanFolder(String folder){if(folder==null||folder.trim().isEmpty())return null;List<String> parts=new ArrayList<>();
        for(String p:folder.split("[/\\\\]")){if(p.isEmpty()||p.equals("."))continue;parts.add(p.equals("..")?"_":FileNames.sanitize(p));if(parts.size()==32)break;}
        return parts.isEmpty()?null:String.join("/",parts);}
    /** A dropped or stalled connection (worth reconnecting), as opposed to an answer from the receiver or a local file problem. */
    private static boolean retryable(Exception e){if(e instanceof SocketTimeoutException||e instanceof SocketException||e instanceof EOFException||e instanceof SSLException)return true;
        String m=e.getMessage();return m!=null&&m.startsWith("timeout");}
    private static void closeQuietly(Link l){if(l!=null)try{l.close();}catch(IOException ignored){}}
    private void rememberAddress(Link l,Device device){Device known=book.get(l.id);if(known!=null){known.addresses.remove(l.address);known.addresses.add(0,l.address);known.port=device.port;known.lastSeen=System.currentTimeMillis();book.put(known);}}

    /** Sends files (with their folders). A very large selection to a device that can't take it in one transfer goes in several. */
    public List<String> send(Device device,List<FileItem> files)throws Exception{if(files.isEmpty())throw new IOException("Choose files first");outgoingCancelled=false;
        List<String> saved=new ArrayList<>();for(int start=0;start<files.size();){int[] taken={0};saved.addAll(sendBatch(device,files,start,taken));start+=taken[0];}return saved;}
    private List<String> sendBatch(Device device,List<FileItem> files,int start,int[] taken)throws Exception{
        Link l=connectAny(device.addresses,device.port,device.id);
        String peerName=device.label();boolean resume;List<Offered> offered=new ArrayList<>();List<FileItem> items=new ArrayList<>();long total=0;
        try{rememberAddress(l,device);boolean folders=l.features.contains("folders"),pages=l.features.contains("pages");resume=l.features.contains("resume");
            // As many files as this receiver takes in one transfer: one offer message for older receivers, up to 10,000 with "pages".
            int budget=0;for(int k=start;k<files.size()&&offered.size()<MAX_FILES;k++){FileItem f=files.get(k);Offered o=new Offered(offered.size(),offered.size(),f.name,f.size,folders?cleanFolder(f.folder):null);
                int size=o.bytes();if(!pages&&!offered.isEmpty()&&budget+size>OFFER_BUDGET)break;budget+=size;offered.add(o);items.add(f);}
            for(Offered o:offered){if(o.size<0){total=-1;break;}total+=o.size;}}
        catch(Exception e){closeQuietly(l);throw e;}
        taken[0]=offered.size();String transferId=UUID.randomUUID().toString();Outgoing state=new Outgoing(offered.size());TransferMeter meter=new TransferMeter(total,System.nanoTime());
        try{for(int attempt=0;;attempt++){
                try{if(l==null){Device latest=book.get(device.id);if(latest==null)latest=device;l=connectAny(latest.addresses,latest.port,latest.id);rememberAddress(l,latest);}
                    sendFiles(l,transferId,offered,items,state,peerName,total,meter);break;}
                catch(Exception e){if(!resume||attempt>=RECONNECTS||outgoingCancelled||e instanceof InterruptedException||!retryable(e))throw e;
                    // The connection dropped (Wi-Fi blip, switching networks): reconnect and continue where it stopped.
                    closeQuietly(l);l=null;events.progress(new Progress(transferId,false,peerName,"",0,offered.size(),state.done,total,0,-1,false,true));Thread.sleep(2000L<<attempt);}}}
        catch(Exception e){if(l!=null)try{Protocol.send(l.out,"type","cancel");}catch(Exception ignored){}
            String error=e instanceof InterruptedException||outgoingCancelled?"cancelled: Transfer cancelled":e.getMessage()==null?"io_error":e.getMessage();
            events.finished(new Result(transferId,false,peerName,state.saved(),offered.size(),state.folder,error,state.done,meter.elapsedMillis(System.nanoTime())));
            if(outgoingCancelled&&!(e instanceof InterruptedException))throw new IOException(error,e);throw e;}
        finally{closeQuietly(l);}
        events.finished(new Result(transferId,false,peerName,state.saved(),offered.size(),state.folder,null,state.done,meter.elapsedMillis(System.nanoTime())));return state.saved();}
    /** Sends the offer, split over "offer_more" messages when the list doesn't fit in one (receivers that list "pages"). */
    private static void writeOffer(Link l,String transferId,List<Offered> offered,long total)throws IOException{List<List<Object>> pages=new ArrayList<>();pages.add(new ArrayList<>());int size=0;
        for(Offered o:offered){int b=o.bytes();if(size+b>OFFER_BUDGET&&!pages.get(pages.size()-1).isEmpty()){pages.add(new ArrayList<>());size=0;}pages.get(pages.size()-1).add(o.entry());size+=b;}
        Protocol.send(l.out,"type","offer","transferId",transferId,"count",offered.size(),"totalBytes",total,"files",pages.get(0),"more",pages.size()>1);
        for(int p=1;p<pages.size();p++)Protocol.send(l.out,"type","offer_more","files",pages.get(p),"more",p<pages.size()-1);}
    /**
     * One connection's worth of a transfer: offers the files the receiver hasn't confirmed yet, continues a half-sent one from
     * where the receiver says it stopped, and records each confirmed file in {@code state}.
     */
    @SuppressWarnings("unchecked")
    private void sendFiles(Link l,String transferId,List<Offered> files,List<FileItem> items,Outgoing state,String peerName,long total,TransferMeter meter)throws Exception{
        outgoingSockets.add(l.s);
        try{if(outgoingCancelled)throw new IOException("cancelled: Transfer cancelled");
            List<Integer> pending=new ArrayList<>();for(int i=0;i<files.size();i++)if(state.savedAs[i]==null)pending.add(i);
            List<Offered> offered=new ArrayList<>();long offeredTotal=0;for(int j=0;j<pending.size();j++){Offered o=files.get(pending.get(j)).at(j);offered.add(o);offeredTotal=o.size<0||offeredTotal<0?-1:offeredTotal+o.size;}
            writeOffer(l,transferId,offered,offeredTotal);
            // A receiver that asks its user first says "pending" (only to senders that list "consent") and may take up to two minutes.
            boolean consent=l.features.contains("consent");if(consent)l.s.setSoTimeout(130000);Map<String,Object> accept;
            while(true){accept=Protocol.message(l.in);if(consent&&"pending".equals(Protocol.type(accept))){events.progress(new Progress(transferId,false,peerName,offered.get(0).name,0,files.size(),state.done,total,0,-1,true,false));continue;}break;}
            l.s.setSoTimeout(idleTimeoutMs);Protocol.expect(accept,"accept");state.folder=Json.str(accept,"folder");
            Set<Integer> skip=new HashSet<>();int partialIndex=-1;long partialOffset=0;Object resumeInfo=accept.get("resume");
            if(resumeInfo instanceof Map){Map<String,Object> r=(Map<String,Object>)resumeInfo;Object doneList=r.get("done");
                if(doneList instanceof List)for(Object item:(List<?>)doneList){Map<String,Object> d=(Map<String,Object>)item;int j=(int)Json.num(d,"i");
                    if(j<0||j>=offered.size()||!skip.add(j))throw new IOException("protocol_error: Invalid resume");state.savedAs[pending.get(j)]=Json.str(d,"savedAs");state.done+=Math.max(0,offered.get(j).size);}
                if(r.containsKey("i")){partialIndex=(int)Json.num(r,"i");partialOffset=Json.num(r,"offset");
                    if(partialIndex<0||partialIndex>=offered.size()||skip.contains(partialIndex)||partialOffset<0||offered.get(partialIndex).size>=0&&partialOffset>offered.get(partialIndex).size)throw new IOException("protocol_error: Invalid resume");}}
            BlockingQueue<Object> replies=new ArrayBlockingQueue<>(offered.size()+4);
            // The receiver is silent while a file uploads (it replies only after file_end), so an idle read timeout here
            // is normal; waiting for each reply is bounded separately by replies.poll(30 s) below.
            workers.execute(()->{try{while(true){Map<String,Object> m;try{m=Protocol.message(l.in);}catch(SocketTimeoutException idle){continue;}replies.put(m);String t=Json.str(m,"type");if(t.equals("done_ok")||t.equals("error")||t.equals("cancel"))break;}}catch(Exception e){try{replies.put(e);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}});
            // File data is read straight into the frame after its 5-byte header, so each chunk is sent without being copied.
            byte[] frame=new byte[5+65536];
            for(int j=0;j<offered.size();j++){if(skip.contains(j))continue;Offered f=offered.get(j);FileItem item=items.get(pending.get(j));long offset=j==partialIndex?partialOffset:0;
                report(meter,true,transferId,false,peerName,f.name,pending.get(j)+1,files.size(),state.done,total);
                if(offset>0)Protocol.send(l.out,"type","file","i",j,"offset",offset);else Protocol.send(l.out,"type","file","i",j);
                MessageDigest hash=MessageDigest.getInstance("SHA-256");
                try(InputStream input=item.opener.open()){
                    // Continuing a half-sent file: the receiver already has these bytes, but the checksum covers the whole file.
                    for(long skipped=0;skipped<offset;){int n=input.read(frame,5,(int)Math.min(65536,offset-skipped));if(n<0)throw new IOException("io_error: File is shorter than before");hash.update(frame,5,n);skipped+=n;}
                    state.done+=offset;
                    int n;while((n=input.read(frame,5,65536))!=-1){if(Thread.currentThread().isInterrupted()||outgoingCancelled)throw new InterruptedException("cancelled: Transfer cancelled");if(n==0)continue;
                        Object early=replies.poll();if(early!=null)checked(early,"file_ok",false);hash.update(frame,5,n);Protocol.writeData(l.out,frame,n);state.done+=n;
                        report(meter,false,transferId,false,peerName,f.name,pending.get(j)+1,files.size(),state.done,total);}}
                Protocol.send(l.out,"type","file_end","i",j,"sha256",Protocol.hex(hash.digest()));Map<String,Object> reply=checked(replies.poll(30,TimeUnit.SECONDS),"file_ok",true);
                if(Json.num(reply,"i")!=j)throw new IOException("protocol_error: Wrong file index");state.savedAs[pending.get(j)]=Json.str(reply,"savedAs");}
            Protocol.send(l.out,"type","done");checked(replies.poll(30,TimeUnit.SECONDS),"done_ok",true);
            report(meter,true,transferId,false,peerName,files.get(files.size()-1).name,files.size(),files.size(),state.done,total);}
        finally{outgoingSockets.remove(l.s);}}
    private void report(TransferMeter meter,boolean force,String transferId,boolean incoming,String peerName,String file,int index,int count,long done,long total){
        long now=System.nanoTime();meter.update(done,now);if(!meter.due(now)&&!force)return;
        events.progress(new Progress(transferId,incoming,peerName,file,index,count,done,total,meter.speed(done,now),meter.secondsLeft(done,now)));}
    private static Map<String,Object> checked(Object reply,String expected,boolean required)throws Exception{if(reply==null){if(required)throw new IOException("timeout: Peer did not respond");return null;}if(reply instanceof Exception)throw(Exception)reply;
        Map<String,Object> m=(Map<String,Object>)reply;String type=Protocol.type(m);if(type.equals("cancel"))throw new IOException("cancelled: Peer cancelled transfer");Protocol.expect(m,expected);return m;}
    /**
     * Asks the user about an offer. Returns the read of the sender's next message, started while waiting: the sender stays
     * silent until it gets an answer, so anything arriving earlier means it cancelled or went away. Throws when declined.
     */
    private Future<Map<String,Object>> ask(SSLSocket s,String transferId,String peerName,List<String> names,long total,boolean senderWaits)throws Exception{
        // Senders from before "consent" give up after 30 s without an answer, so they get a shorter window.
        long window=senderWaits?120000:25000;Offer offer=new Offer(transferId,peerName,names,total,System.currentTimeMillis()+window);
        if(!events.offer(offer))return null;
        try{if(senderWaits){Protocol.send(s.getOutputStream(),"type","pending");s.setSoTimeout((int)window+10000);}
            Future<Map<String,Object>> next=workers.submit(()->Protocol.message(s.getInputStream()));
            while(!offer.done.await(200,TimeUnit.MILLISECONDS)){if(next.isDone())throw new IOException("cancelled: The sender cancelled");if(System.currentTimeMillis()>offer.deadline)break;}
            if(offer.done.getCount()>0||!offer.accepted)throw new IOException("declined: The files weren't accepted");
            return next;}
        finally{offer.answer(false);events.offerClosed(offer);}}
    /** Reads the offered files, including the "offer_more" pages that follow when the list didn't fit in one message. */
    @SuppressWarnings("unchecked")
    private static List<Offered> readOffered(SSLSocket s,Map<String,Object> offer,int count)throws IOException{List<Offered> files=new ArrayList<>();Map<String,Object> page=offer;
        while(true){Object list=page.get("files");if(!(list instanceof List))throw new IOException("protocol_error: Invalid offer");
            for(Object item:(List<?>)list){Map<String,Object> f=(Map<String,Object>)item;int index=(int)Json.num(f,"i");long size=Json.num(f,"size");
                if(index!=files.size()||size<-1||files.size()>=count)throw new IOException("protocol_error: Invalid offer");
                Object path=f.get("path");Object k=f.get("k");int key=k instanceof Number?((Number)k).intValue():index;if(key<0||key>=MAX_FILES)throw new IOException("protocol_error: Invalid offer");
                files.add(new Offered(index,key,FileNames.sanitize(Json.str(f,"name")),size,path instanceof String?cleanFolder((String)path):null));}
            if(!Boolean.TRUE.equals(page.get("more")))break;
            page=Protocol.message(s.getInputStream());if(!"offer_more".equals(Protocol.type(page)))throw new IOException("protocol_error: Expected offer_more");}
        if(files.size()!=count)throw new IOException("protocol_error: Invalid offer");return files;}
    /** Where a received file goes: its sent folder under the receive folder. A top folder that already exists gets a new name ("Photos (1)"). */
    private String targetFolder(String folder,Map<String,String> tops){if(folder==null)return null;int slash=folder.indexOf('/');String top=slash<0?folder:folder.substring(0,slash);
        synchronized(folderLock){String mapped=tops.get(top);if(mapped==null){mapped=storage.uniqueFolder(top);for(int n=1;tops.containsValue(mapped);n++)mapped=top+" ("+n+")";tops.put(top,mapped);}
            return slash<0?mapped:mapped+folder.substring(slash);}}
    @SuppressWarnings("unchecked")
    private void receive(SSLSocket s,Map<String,Object> offer,String peerId,String peerName,boolean trusted,Set<String> senderFeatures)throws Exception{
        int count=(int)Json.num(offer,"count");long total=Json.num(offer,"totalBytes");
        if(count<1||count>MAX_FILES||total< -1){error(s,"protocol_error","Invalid offer");return;}
        List<Offered> files=readOffered(s,offer,count);long sum=0;for(Offered f:files){if(f.size<0){sum=-1;break;}sum+=f.size;}
        if(total>=0&&sum!=total){error(s,"protocol_error","Invalid total size");return;}
        String transferId=Json.str(offer,"transferId");boolean canResume=senderFeatures.contains("resume");String key=peerId+"/"+transferId;
        // The same transfer coming back after a dropped connection: skip what's saved, continue the half-received file.
        Interrupted earlier=interrupted.remove(key);if(earlier!=null){if(earlier.expiry!=null)earlier.expiry.cancel(false);trusted=earlier.trusted;}
        if(earlier==null&&total>=0&&storage.freeBytes()<total+16777216L){error(s,"no_space","Not enough free space");return;}
        Future<Map<String,Object>> first=null;
        if(earlier==null&&askBeforeReceiving&&!trusted){List<String> names=new ArrayList<>();for(Offered f:files)names.add(f.folder==null?f.name:f.folder+"/"+f.name);
            first=ask(s,transferId,peerName,names,total,senderFeatures.contains("consent"));}
        s.setSoTimeout(idleTimeoutMs);
        String folder=earlier!=null?earlier.folder:storage.folder();int offeredCount=earlier!=null?earlier.offered:count;long started=earlier!=null?earlier.startedNanos:System.nanoTime();
        List<Offered> completedFiles=earlier!=null?earlier.completedFiles:new ArrayList<>();List<String> saved=earlier!=null?earlier.completedNames:new ArrayList<>();
        Map<String,String> tops=earlier!=null?earlier.folders:new HashMap<>();Map<Integer,String> already=new HashMap<>();int partialIndex=-1;
        if(earlier!=null){List<Integer> unmatched=new ArrayList<>();for(int c=0;c<completedFiles.size();c++)unmatched.add(c);
            for(int j=0;j<files.size();j++){Offered f=files.get(j);Integer match=null;for(Integer c:unmatched)if(completedFiles.get(c).same(f)){match=c;break;}
                if(match!=null){already.put(j,saved.get(match));unmatched.remove(match);}else if(partialIndex<0&&earlier.partial!=null&&earlier.partial.same(f))partialIndex=j;}
            if(partialIndex<0)earlier.dropPartial();}
        Map<String,Object> accept=Json.obj("type","accept","folder",folder);
        if(earlier!=null){List<Object> doneList=new ArrayList<>();for(Map.Entry<Integer,String> a:already.entrySet())doneList.add(Json.obj("i",a.getKey(),"savedAs",a.getValue()));
            Map<String,Object> resume=Json.obj("done",doneList);if(partialIndex>=0){resume.put("i",partialIndex);resume.put("offset",earlier.partialWritten);}accept.put("resume",resume);}
        Protocol.sendObject(s.getOutputStream(),accept);
        long done=earlier!=null?earlier.bytes:0;Storage.Output current=null;Offered currentFile=null;MessageDigest hash=null;long written=0;
        TransferMeter meter=new TransferMeter(total,System.nanoTime());Protocol.Reader reader=new Protocol.Reader(s.getInputStream(),Protocol.MAX_DATA);
        try{for(int i=0;i<files.size();i++){if(already.containsKey(i))continue;Offered file=files.get(i);
                report(meter,true,transferId,true,peerName,file.name,saved.size()+1,offeredCount,done,total);
                Map<String,Object> begin;if(first!=null){try{begin=first.get(30,TimeUnit.SECONDS);}catch(TimeoutException late){throw new IOException("timeout: Sender timed out");}catch(ExecutionException failed){throw failed.getCause() instanceof Exception?(Exception)failed.getCause():failed;}first=null;}
                else begin=Protocol.message(s.getInputStream());if("cancel".equals(Json.str(begin,"type")))throw new IOException("cancelled: Sender cancelled");Protocol.expect(begin,"file");if(Json.num(begin,"i")!=i)throw new IOException("protocol_error: Wrong file index");
                Object o=begin.get("offset");long offset=o instanceof Number?((Number)o).longValue():0;currentFile=file;
                if(i==partialIndex&&earlier.partialOutput!=null&&offset>0&&offset==earlier.partialWritten){current=earlier.partialOutput;hash=earlier.partialHash;written=offset;earlier.partialOutput=null;earlier.partialHash=null;}
                else{if(offset!=0)throw new IOException("protocol_error: Unexpected offset");if(i==partialIndex)earlier.dropPartial();current=storage.begin(targetFolder(file.folder,tops),file.name);hash=MessageDigest.getInstance("SHA-256");written=0;}
                while(true){if(reader.next()==2){int n=reader.length();if(file.size>=0&&written+n>file.size)throw new IOException("io_error: File exceeds offered size");
                        current.stream().write(reader.data(),0,n);hash.update(reader.data(),0,n);written+=n;done+=n;report(meter,false,transferId,true,peerName,file.name,saved.size()+1,offeredCount,done,total);continue;}
                    Map<String,Object> end=reader.message();if(Json.str(end,"type").equals("cancel"))throw new IOException("cancelled: Sender cancelled");Protocol.expect(end,"file_end");if(Json.num(end,"i")!=i)throw new IOException("protocol_error: Wrong file index");if(file.size>=0&&written!=file.size)throw new IOException("io_error: File size differs");
                    if(!Protocol.hex(hash.digest()).equalsIgnoreCase(Json.str(end,"sha256")))throw new IOException("checksum_mismatch: File checksum differs");break;}
                String finalName=current.finish();current=null;currentFile=null;completedFiles.add(file);saved.add(finalName);Protocol.send(s.getOutputStream(),"type","file_ok","i",i,"savedAs",finalName);}
            Protocol.expect(Protocol.message(s.getInputStream()),"done");Protocol.send(s.getOutputStream(),"type","done_ok","saved",saved.size());
            events.finished(new Result(transferId,true,peerName,saved,offeredCount,folder,null,done,(System.nanoTime()-started)/1000000));}
        catch(Exception e){
            if(canResume&&!cancelledSockets.contains(s)&&retryable(e)){
                // The connection dropped: keep what arrived (and the open half-received file) for the sender to continue.
                Interrupted kept=new Interrupted(transferId,peerName,folder,offeredCount,trusted,started,completedFiles,saved,tops,done);
                if(current!=null){kept.partial=currentFile;kept.partialOutput=current;kept.partialHash=hash;kept.partialWritten=written;current=null;}
                interrupted.put(key,kept);kept.expiry=timers.schedule(()->expire(key,kept),keepInterruptedMs,TimeUnit.MILLISECONDS);
                events.progress(new Progress(transferId,true,peerName,currentFile==null?"":currentFile.name,saved.size()+1,offeredCount,done,total,0,-1,false,true));return;}
            if(current!=null)current.abort();String msg=cancelledSockets.contains(s)?"cancelled: You cancelled the transfer":e.getMessage()==null?"io_error: Transfer stopped":e.getMessage();String code=msg.contains(":")?msg.substring(0,msg.indexOf(':')):"io_error";error(s,code,msg);
            events.finished(new Result(transferId,true,peerName,saved,offeredCount,folder,msg,done,(System.nanoTime()-started)/1000000));}}
    /** Gives up on an interrupted incoming transfer the sender didn't come back for. */
    private void expire(String key,Interrupted kept){if(!interrupted.remove(key,kept))return;kept.dropPartial();
        events.finished(new Result(kept.transferId,true,kept.peerName,kept.completedNames,kept.offered,kept.folder,"disconnected: The sender didn't come back",kept.bytes,(System.nanoTime()-kept.startedNanos)/1000000));}
    public void stop(){running=false;try{if(listener!=null)listener.close();}catch(IOException ignored){}}
    public void close(){stop();workers.shutdownNow();timers.shutdownNow();for(Interrupted kept:interrupted.values())kept.dropPartial();interrupted.clear();}
}
