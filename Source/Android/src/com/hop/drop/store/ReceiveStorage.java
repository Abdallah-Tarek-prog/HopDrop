package com.hop.drop.store;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import com.hop.drop.core.FileNames;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where received files go: a folder the user picked (Storage Access Framework), else Download/HopDrop (MediaStore on
 * Android 10+, plain files before). Files from a sent folder go into the same folder structure inside it.
 */
public final class ReceiveStorage implements Peer.Storage {
    private static final Object UNIQUE_LOCK=new Object();
    /**
     * Names already taken in a folder, and folders already found in a picked tree, read once and kept up to date while files
     * keep arriving there. Asking Android again for every file cost about 60 ms a file on a Realme (Android 11), most of a
     * small file's time. Forgotten after a short pause, so changes made outside HopDrop are picked up by the next transfer.
     */
    private static final Map<String,Cached> CACHE=new HashMap<>();
    private static final long CACHE_MS=30000;
    private static final class Cached{final Object value;long used;Cached(Object value){this.value=value;}}
    private interface Load<T>{T get()throws IOException;}
    /** The kept value for {@code key}, loaded when missing or stale. Callers hold UNIQUE_LOCK. */
    @SuppressWarnings("unchecked")
    private static <T> T cached(String key,Load<T> load)throws IOException{long now=SystemClock.elapsedRealtime();CACHE.values().removeIf(c->now-c.used>CACHE_MS);
        Cached c=CACHE.get(key);if(c==null){c=new Cached(load.get());CACHE.put(key,c);}c.used=now;return (T)c.value;}
    /** Something failed: read everything again next time rather than trust what's kept. */
    private static void forget(){synchronized(UNIQUE_LOCK){CACHE.clear();}}
    private static String lower(String name){return name.toLowerCase(java.util.Locale.ROOT);}
    private static final String DOWNLOADS="Download/HopDrop/";
    private static final String BACKUPS="Download/HopDrop/Backups/";
    private final Context context;private final SharedPreferences settings;private final ContentResolver resolver;
    public ReceiveStorage(Context c){context=c;settings=c.getSharedPreferences("settings_v2",Context.MODE_PRIVATE);resolver=c.getContentResolver();}
    private Uri tree(){return tree("folder");}
    private Uri tree(String key){String s=settings.getString(key,null);if(s==null)return null;Uri u=Uri.parse(s);
        for(android.content.UriPermission p:resolver.getPersistedUriPermissions())if(p.getUri().equals(u)&&p.isWritePermission())return u;
        settings.edit().remove(key).apply();android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        manager.notify(440,((com.hop.drop.HopApp)context.getApplicationContext()).notification("results",key.equals("folder")?"Receive folder unavailable":"Backup folder unavailable",
            key.equals("folder")?"Saving to Download/HopDrop instead":"Saving to Download/HopDrop/Backups instead",false,-1).build());return null;}
    public String folder(){Uri t=tree();return t==null?"Download/HopDrop":t.getLastPathSegment();}
    public String backupFolder(){Uri t=tree("backup_folder");if(t==null)return "Download/HopDrop/Backups";
        try(Cursor c=resolver.query(DocumentsContract.buildDocumentUriUsingTree(t,DocumentsContract.getTreeDocumentId(t)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);
        }catch(Exception ignored){}return t.getLastPathSegment();}
    public long freeBytes(){return freeBytes(tree());}
    public long backupFreeBytes(){return freeBytes(tree("backup_folder"));}
    private long freeBytes(Uri t){if(t!=null){try{String documentId=DocumentsContract.getTreeDocumentId(t);Uri roots=DocumentsContract.buildRootsUri(t.getAuthority());
            try(Cursor c=resolver.query(roots,new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_AVAILABLE_BYTES},null,null,null)){
                if(c!=null)while(c.moveToNext()){String rootId=c.getString(0);if((documentId.equals(rootId)||documentId.startsWith(rootId+":"))&&!c.isNull(1))return c.getLong(1);}}}catch(Exception ignored){}return Long.MAX_VALUE;}
        try{File root=Environment.getExternalStorageDirectory();return new StatFs(root.getAbsolutePath()).getAvailableBytes();}catch(Exception e){return Long.MAX_VALUE;}}
    public Peer.Storage.Output begin(String name)throws IOException{return begin(null,name);}
    public Peer.Storage.Output begin(String folder,String name)throws IOException{Uri t=tree();return t!=null?new SafOutput(t,folder,name):Build.VERSION.SDK_INT>=29?new MediaOutput(folder,name):new LegacyOutput(folder,name);}
    public Peer.Storage.Output beginBackup(String folder,String name)throws IOException{Uri t=tree("backup_folder");return t!=null?new SafOutput(t,folder,name):Build.VERSION.SDK_INT>=29?new MediaOutput(folder,name,BACKUPS):new LegacyOutput(folder,name,new File(legacyRoot(),"Backups"));}
    public static final class BackupEntry {
        public final String id,device,folder,location,place;public final int count;public final long lastRun;
        BackupEntry(String id,String device,String folder,int count,long lastRun,String location,String place){this.id=id;this.device=device;this.folder=folder;this.count=count;this.lastRun=lastRun;this.location=location;this.place=place;}
    }
    public static List<BackupEntry> backups(Context context){String value=context.getSharedPreferences("settings_v2",Context.MODE_PRIVATE).getString("received_backups","{\"entries\":[]}");List<BackupEntry> result=new ArrayList<>();
        try{for(Object item:(List<?>)Json.parseObject(value).get("entries")){Map<?,?> m=(Map<?,?>)item;result.add(new BackupEntry((String)m.get("id"),(String)m.get("device"),(String)m.get("folder"),((Number)m.get("count")).intValue(),((Number)m.get("lastRun")).longValue(),m.get("location") instanceof String?(String)m.get("location"):"default",m.get("place") instanceof String?(String)m.get("place"):"Download/HopDrop/Backups"));}}catch(Exception ignored){}return result;}
    private static synchronized void saveBackups(Context context,List<BackupEntry> entries){List<Object> list=new ArrayList<>();for(BackupEntry e:entries)list.add(Json.obj("id",e.id,"device",e.device,"folder",e.folder,"count",e.count,"lastRun",e.lastRun,"location",e.location,"place",e.place));context.getSharedPreferences("settings_v2",Context.MODE_PRIVATE).edit().putString("received_backups",Json.string(Json.obj("entries",list))).commit();}
    public static synchronized void forgetBackup(Context context,String id,String folder,String location){List<BackupEntry> list=backups(context);list.removeIf(e->e.id.equals(id)&&e.folder.equals(folder)&&e.location.equals(location));saveBackups(context,list);}
    public void backupReceived(String id,String device,String folder,int count){String location=settings.getString("backup_folder","default"),place=backupFolder();synchronized(ReceiveStorage.class){List<BackupEntry> list=backups(context);BackupEntry old=null;for(BackupEntry e:list)if(e.id.equals(id)&&e.folder.equals(folder)&&e.location.equals(location)){old=e;break;}if(old!=null)list.remove(old);list.add(new BackupEntry(id,device,folder,(old==null?0:old.count)+count,System.currentTimeMillis(),location,place));saveBackups(context,list);}((com.hop.drop.HopApp)context.getApplicationContext()).changed();}
    public String uniqueFolder(String name){synchronized(UNIQUE_LOCK){Uri t=tree();
        if(t!=null){Uri root=DocumentsContract.buildDocumentUriUsingTree(t,DocumentsContract.getTreeDocumentId(t));Set<String> taken=names(root);return FileNames.unique(name,n->taken.contains(n.toLowerCase(java.util.Locale.ROOT)));}
        if(Build.VERSION.SDK_INT>=29)return FileNames.unique(name,this::mediaFolderExists);
        File base=legacyRoot();return FileNames.unique(name,n->new File(base,n).exists());}}
    private static String joined(String folder,String name){return folder==null?name:folder+"/"+name;}
    /** Lower-case names of the documents directly inside {@code parent}. */
    private Set<String> names(Uri parent){Set<String> found=new HashSet<>();try(Cursor c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent)),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
        if(c!=null)while(c.moveToNext())found.add(c.getString(0).toLowerCase(java.util.Locale.ROOT));}catch(Exception ignored){}return found;}
    /** The folder document for {@code folder} ("Photos/2024") under the picked tree, created as needed. */
    private Uri safFolder(Uri tree,String folder)throws IOException{Uri current=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));if(folder==null)return current;
        for(String part:folder.split("/")){Uri child=null;
            try(Cursor c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(current,DocumentsContract.getDocumentId(current)),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                if(c!=null)while(c.moveToNext())if(part.equalsIgnoreCase(c.getString(1))&&DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))){child=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));break;}}
            if(child==null){try{child=DocumentsContract.createDocument(resolver,current,DocumentsContract.Document.MIME_TYPE_DIR,part);}catch(Exception e){throw new IOException("Cannot create folder "+part,e);}if(child==null)throw new IOException("Cannot create folder "+part);}
            current=child;}
        return current;}
    private boolean mediaFolderExists(String name){try(Cursor c=resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns._ID},MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?",new String[]{DOWNLOADS+name+"/%"},null)){
        return c!=null&&c.moveToFirst();}catch(Exception e){return false;}}
    private static File legacyRoot(){return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"HopDrop");}
    private final class SafOutput implements Peer.Storage.Output {
        private final String folder,name;private final Uri parent;private Uri temp;private OutputStream stream;private final SaveTimings.Clock clock=SaveTimings.start();
        SafOutput(Uri tree,String folder,String name)throws IOException{this.folder=folder;this.name=name;try{synchronized(UNIQUE_LOCK){parent=cached("folder:"+tree+"|"+folder,()->safFolder(tree,folder));}clock.end(SaveTimings.FOLDER);
            temp=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",".hopdrop-"+UUID.randomUUID()+".part");clock.end(SaveTimings.CREATE);if(temp==null)throw new IOException("Cannot create file in selected folder");stream=resolver.openOutputStream(temp,"w");clock.end(SaveTimings.OPEN);if(stream==null)throw new IOException("Cannot open selected folder");stream=clock.wrap(stream);}catch(IOException e){forget();throw e;}catch(Exception e){forget();throw new IOException("Receive folder is unavailable: "+e.getMessage(),e);}}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{try{clock.begin();stream.close();clock.end(SaveTimings.CLOSE);synchronized(UNIQUE_LOCK){Set<String> taken=cached("names:"+parent,()->names(parent));String finalName=FileNames.unique(name,n->taken.contains(lower(n)));clock.end(SaveTimings.NAME);
            Uri renamed=DocumentsContract.renameDocument(resolver,temp,finalName);if(renamed==null)throw new IOException("Could not rename received file");taken.add(lower(finalName));clock.end(SaveTimings.PUBLISH);clock.done();temp=null;return joined(folder,finalName);}}catch(Exception e){forget();abort();throw new IOException(e);}}
        public void abort(){try{if(stream!=null)stream.close();if(temp!=null)DocumentsContract.deleteDocument(resolver,temp);}catch(Exception ignored){}}
    }
    private final class MediaOutput implements Peer.Storage.Output {
        private final String folder,name,relative;private Uri uri;private OutputStream stream;private final SaveTimings.Clock clock=SaveTimings.start();
        MediaOutput(String folder,String name)throws IOException{this(folder,name,DOWNLOADS);}
        MediaOutput(String folder,String name,String root)throws IOException{this.folder=folder;this.name=name;relative=folder==null?root:root+folder+"/";ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,".hopdrop-"+UUID.randomUUID()+".part");v.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");v.put(MediaStore.MediaColumns.RELATIVE_PATH,relative);v.put(MediaStore.MediaColumns.IS_PENDING,1);
            clock.begin();uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);clock.end(SaveTimings.CREATE);if(uri==null)throw new IOException("Cannot create download");stream=resolver.openOutputStream(uri,"w");clock.end(SaveTimings.OPEN);if(stream==null)throw new IOException("Cannot write download");stream=clock.wrap(stream);}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{try{clock.begin();stream.close();clock.end(SaveTimings.CLOSE);synchronized(UNIQUE_LOCK){Set<String> taken=cached("media:"+relative,this::names);String finalName=FileNames.unique(name,n->taken.contains(lower(n)));clock.end(SaveTimings.NAME);ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);v.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(uri,v,null,null)!=1)throw new IOException("Cannot finish download");taken.add(lower(finalName));clock.end(SaveTimings.PUBLISH);clock.done();uri=null;return joined(folder,finalName);}}catch(Exception e){forget();abort();throw new IOException(e);}}
        /** Lower-case names of the files HopDrop can see in this folder. */
        private Set<String> names(){Set<String> found=new HashSet<>();try(Cursor c=resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns.DISPLAY_NAME},MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{relative},null)){
            if(c!=null)while(c.moveToNext())found.add(lower(c.getString(0)));}catch(Exception ignored){}return found;}
        public void abort(){try{if(stream!=null)stream.close();if(uri!=null)resolver.delete(uri,null,null);}catch(Exception ignored){}}
    }
    private static final class LegacyOutput implements Peer.Storage.Output {
        private final File directory,temp;private final String folder,name;private final OutputStream stream;
        LegacyOutput(String folder,String name)throws IOException{this(folder,name,legacyRoot());}
        LegacyOutput(String folder,String name,File root)throws IOException{this.folder=folder;this.name=name;directory=folder==null?root:new File(root,folder);if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create "+directory);temp=new File(directory,".hopdrop-"+UUID.randomUUID()+".part");stream=new FileOutputStream(temp);}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{stream.close();synchronized(UNIQUE_LOCK){String finalName=FileNames.unique(name,n->{String[] all=directory.list();if(all!=null)for(String f:all)if(f.equalsIgnoreCase(n))return true;return false;});if(!temp.renameTo(new File(directory,finalName)))throw new IOException("Cannot finish received file");return joined(folder,finalName);}}
        public void abort(){try{stream.close();}catch(Exception ignored){}temp.delete();}
    }
}
