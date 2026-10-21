/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * A folder picked with the system folder picker, which the app has a persisted permission to write to.
 * Unlike the media store, it can be any folder, and it can hold any type of file.
 */
public final class DocumentTree {

    private static final String PRIMARY_VOLUME = "primary";

    private DocumentTree() {
    }

    public static boolean isTree(@Nullable String folder) {
        return folder != null && folder.startsWith(ContentResolver.SCHEME_CONTENT + "://");
    }

    public static boolean hasPermission(String tree) {
        Uri treeUri = Uri.parse(tree);
        for (UriPermission permission : getResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    public static void takePermission(Uri treeUri) {
        getResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /**
     * Releases the permissions of folders that are no longer used, so the app keeps access only to used folders.
     */
    public static void releaseUnusedPermissions(Collection<String> usedTrees) {
        ContentResolver resolver = getResolver();
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            if (DocumentsContract.isTreeUri(uri) && !usedTrees.contains(uri.toString())) {
                resolver.releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
    }

    /**
     * @return The path of the folder, with the volume id for folders that are not on the internal storage.
     */
    public static String getLabel(String tree) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            final int separatorIndex = documentId.indexOf(':');
            if (separatorIndex < 0) return documentId;

            String volume = documentId.substring(0, separatorIndex);
            String path = documentId.substring(separatorIndex + 1);
            if (volume.equals(PRIMARY_VOLUME)) return path;
            return path.isEmpty() ? volume : volume + "/" + path;
        } catch (Exception ex) {
            Logger.printException(() -> "getLabel failure: " + tree, ex);
            return tree;
        }
    }

    /**
     * @param subfolders Subfolders of the tree separated by '/', which are created if they don't exist.
     * @param mimeType   Can be a wildcard type, then the type is guessed from the file name.
     * @return The new file, which the caller writes to.
     */
    public static Uri createFile(String tree, @Nullable String subfolders, String name, @Nullable String mimeType)
            throws IOException {
        ContentResolver resolver = getResolver();
        Uri treeUri = Uri.parse(tree);
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        if (subfolders != null) {
            for (String subfolder : subfolders.split("/")) {
                documentId = findOrCreateFolder(resolver, treeUri, documentId, subfolder);
            }
        }

        Uri file = DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), getMimeType(name, mimeType), name);
        if (file == null) throw new IOException("Could not create file: " + name);
        return file;
    }

    public static void copy(File source, Uri target) throws IOException {
        try (OutputStream output = getResolver().openOutputStream(target)) {
            if (output == null) throw new IOException("Could not open: " + target);
            Files.copy(source.toPath(), output);
        }
    }

    private static String findOrCreateFolder(ContentResolver resolver, Uri treeUri, String parentId, String name)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        try (Cursor cursor = resolver.query(children, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (name.equals(cursor.getString(1)) && Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    return cursor.getString(0);
                }
            }
        }

        Uri folder = DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId), Document.MIME_TYPE_DIR, name);
        if (folder == null) throw new IOException("Could not create folder: " + name);
        return DocumentsContract.getDocumentId(folder);
    }

    /**
     * Providers name files after a specific type, so an unknown type keeps the extension of the name.
     */
    private static String getMimeType(String name, @Nullable String mimeType) {
        if (mimeType != null && !mimeType.endsWith("/*")) return mimeType;

        final int extensionIndex = name.lastIndexOf('.');
        String guessed = extensionIndex < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(extensionIndex + 1).toLowerCase(Locale.ROOT));
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static ContentResolver getResolver() {
        return Utils.getContext().getContentResolver();
    }
}
