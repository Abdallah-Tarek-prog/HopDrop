package com.hop.drop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SharedTextProvider extends ContentProvider {
    public static Uri create(android.content.Context c,String text)throws Exception{String name=UUID.randomUUID()+".txt";File dir=new File(c.getCacheDir(),"shared");if(!dir.exists()&&!dir.mkdirs())throw new FileNotFoundException("Cannot create shared text");
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){out.write(text.getBytes(StandardCharsets.UTF_8));}
        return new Uri.Builder().scheme("content").authority(c.getPackageName()+".sharedtext").appendPath(name).build();}
    private File file(Uri uri)throws FileNotFoundException{String n=uri.getLastPathSegment();java.util.List<String> parts=uri.getPathSegments();
        if(parts.size()>=2&&parts.size()<=34&&"received".equals(parts.get(0))){
            // Received files, also inside received folders: every part must already be a safe name, so nothing outside Download/HopDrop is reachable.
            File target=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"HopDrop");
            for(String part:parts.subList(1,parts.size())){if(!com.hop.drop.core.FileNames.sanitize(part).equals(part))throw new FileNotFoundException("Invalid file name");target=new File(target,part);}
            return target;}
        if(n==null||!n.matches("[0-9a-f-]{36}\\.txt"))throw new FileNotFoundException("Invalid shared text");return new File(new File(getContext().getCacheDir(),"shared"),n);}
    public boolean onCreate(){return true;}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"Shared text.txt",f.length()});return c;}catch(Exception e){return null;}}
    public String getType(Uri uri){String guessed=java.net.URLConnection.guessContentTypeFromName(uri.getLastPathSegment());return guessed==null?"application/octet-stream":guessed;}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
