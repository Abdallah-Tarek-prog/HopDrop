package com.hop.drop;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder picked with Android's folder picker. It stays one item in the Send list and travels as a folder: its files
 * are only listed when it's counted and when it's sent, so a folder of thousands of files never has to pass through
 * the screen or between HopDrop's parts as thousands of separate entries.
 */
final class FolderScan {
    /** At most this many files are read from one folder. */
    static final int LIMIT = 50000;

    static final class Item {
        final Uri uri;
        final String name;
        /** Where the file sits: "Photos" for the folder itself, "Photos/2024" for a subfolder. */
        final String folder;
        /** Bytes, or -1 when the provider doesn't say. */
        final long size;

        Item(Uri uri, String name, String folder, long size) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
            this.size = size;
        }
    }

    private FolderScan() {
    }

    static String name(ContentResolver resolver, Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        try (Cursor c = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "Folder";
    }

    /** Every file in the folder and its subfolders, leaving out hidden ".name" files and folders. */
    static List<Item> files(ContentResolver resolver, Uri tree) {
        List<Item> found = new ArrayList<>();
        ArrayDeque<String[]> pending = new ArrayDeque<>();
        pending.add(new String[]{DocumentsContract.getTreeDocumentId(tree), name(resolver, tree)});
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE};
        while (!pending.isEmpty() && found.size() < LIMIT) {
            String[] next = pending.poll();
            try (Cursor c = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, next[0]), columns,
                    null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext() && found.size() < LIMIT) {
                    String name = c.getString(1);
                    if (name == null || name.startsWith(".")) continue;
                    if (Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                        pending.add(new String[]{c.getString(0), next[1] + "/" + name});
                    } else {
                        found.add(new Item(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name,
                                next[1], c.isNull(3) ? -1 : c.getLong(3)));
                    }
                }
            }
        }
        return found;
    }
}
