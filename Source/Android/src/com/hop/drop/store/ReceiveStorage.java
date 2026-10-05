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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import com.hop.drop.core.FileNames;
import com.hop.drop.core.Peer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Where received files go: a folder the user picked (Storage Access Framework), else Download/HopDrop (MediaStore on
 * Android 10+, plain files before). Files from a sent folder go into the same folder structure inside it.
 */
public final class ReceiveStorage implements Peer.Storage {
    private static final Object UNIQUE_LOCK=new Object();
    private static final String DOWNLOADS="Download/HopDrop/";
    private final Context context;private final SharedPreferences settings;private final ContentResolver resolver;
    public ReceiveStorage(Context c){context=c;settings=c.getSharedPreferences("settings_v2",Context.MODE_PRIVATE);resolver=c.getContentResolver();}
    private Uri tree(){String s=settings.getString("folder",null);if(s==null)return null;Uri u=Uri.parse(s);
        for(android.content.UriPermission p:resolver.getPersistedUriPermissions())if(p.getUri().equals(u)&&p.isWritePermission())return u;
        settings.edit().remove("folder").apply();android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        manager.notify(440,((com.hop.drop.HopApp)context.getApplicationContext()).notification("results","Receive folder unavailable","Saving to Download/HopDrop instead",false,-1).build());return null;}
    public String folder(){Uri t=tree();return t==null?"Download/HopDrop":t.getLastPathSegment();}
    public long freeBytes(){Uri t=tree();if(t!=null){try{String documentId=DocumentsContract.getTreeDocumentId(t);Uri roots=DocumentsContract.buildRootsUri(t.getAuthority());
            try(Cursor c=resolver.query(roots,new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_AVAILABLE_BYTES},null,null,null)){
                if(c!=null)while(c.moveToNext()){String rootId=c.getString(0);if((documentId.equals(rootId)||documentId.startsWith(rootId+":"))&&!c.isNull(1))return c.getLong(1);}}}catch(Exception ignored){}return Long.MAX_VALUE;}
        try{File root=Environment.getExternalStorageDirectory();return new StatFs(root.getAbsolutePath()).getAvailableBytes();}catch(Exception e){return Long.MAX_VALUE;}}
    public Peer.Storage.Output begin(String name)throws IOException{return begin(null,name);}
    public Peer.Storage.Output begin(String folder,String name)throws IOException{Uri t=tree();return t!=null?new SafOutput(t,folder,name):Build.VERSION.SDK_INT>=29?new MediaOutput(folder,name):new LegacyOutput(folder,name);}
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
        private final String folder,name;private final Uri parent;private Uri temp;private OutputStream stream;
        SafOutput(Uri tree,String folder,String name)throws IOException{this.folder=folder;this.name=name;try{parent=safFolder(tree,folder);
            temp=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",".hopdrop-"+UUID.randomUUID()+".part");if(temp==null)throw new IOException("Cannot create file in selected folder");stream=resolver.openOutputStream(temp,"w");if(stream==null)throw new IOException("Cannot open selected folder");}catch(IOException e){throw e;}catch(Exception e){throw new IOException("Receive folder is unavailable: "+e.getMessage(),e);}}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{try{stream.close();synchronized(UNIQUE_LOCK){Set<String> taken=names(parent);String finalName=FileNames.unique(name,n->taken.contains(n.toLowerCase(java.util.Locale.ROOT)));
            Uri renamed=DocumentsContract.renameDocument(resolver,temp,finalName);if(renamed==null)throw new IOException("Could not rename received file");temp=null;return joined(folder,finalName);}}catch(Exception e){abort();throw new IOException(e);}}
        public void abort(){try{if(stream!=null)stream.close();if(temp!=null)DocumentsContract.deleteDocument(resolver,temp);}catch(Exception ignored){}}
    }
    private final class MediaOutput implements Peer.Storage.Output {
        private final String folder,name,relative;private Uri uri;private OutputStream stream;
        MediaOutput(String folder,String name)throws IOException{this.folder=folder;this.name=name;relative=folder==null?DOWNLOADS:DOWNLOADS+folder+"/";ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,".hopdrop-"+UUID.randomUUID()+".part");v.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");v.put(MediaStore.MediaColumns.RELATIVE_PATH,relative);v.put(MediaStore.MediaColumns.IS_PENDING,1);
            uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("Cannot create download");stream=resolver.openOutputStream(uri,"w");if(stream==null)throw new IOException("Cannot write download");}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{try{stream.close();synchronized(UNIQUE_LOCK){String finalName=FileNames.unique(name,this::exists);ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);v.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(uri,v,null,null)!=1)throw new IOException("Cannot finish download");uri=null;return joined(folder,finalName);}}catch(Exception e){abort();throw new IOException(e);}}
        private boolean exists(String n){try(Cursor c=resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns.DISPLAY_NAME},MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{relative},null)){
            if(c!=null)while(c.moveToNext())if(n.equalsIgnoreCase(c.getString(0)))return true;return false;}catch(Exception e){return false;}}
        public void abort(){try{if(stream!=null)stream.close();if(uri!=null)resolver.delete(uri,null,null);}catch(Exception ignored){}}
    }
    private static final class LegacyOutput implements Peer.Storage.Output {
        private final File directory,temp;private final String folder,name;private final OutputStream stream;
        LegacyOutput(String folder,String name)throws IOException{this.folder=folder;this.name=name;directory=folder==null?legacyRoot():new File(legacyRoot(),folder);if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create "+directory);temp=new File(directory,".hopdrop-"+UUID.randomUUID()+".part");stream=new FileOutputStream(temp);}
        public OutputStream stream(){return stream;}
        public String finish()throws IOException{stream.close();synchronized(UNIQUE_LOCK){String finalName=FileNames.unique(name,n->{String[] all=directory.list();if(all!=null)for(String f:all)if(f.equalsIgnoreCase(n))return true;return false;});if(!temp.renameTo(new File(directory,finalName)))throw new IOException("Cannot finish received file");return joined(folder,finalName);}}
        public void abort(){try{stream.close();}catch(Exception ignored){}temp.delete();}
    }
}
