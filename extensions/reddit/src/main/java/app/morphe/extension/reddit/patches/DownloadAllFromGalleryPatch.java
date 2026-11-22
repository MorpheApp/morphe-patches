/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Pair;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.PostGallery;
import com.reddit.domain.model.PostGalleryItem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("unused")
public final class DownloadAllFromGalleryPatch {

    /**
     * Added to the share sheet handler during patching.
     */
    public interface DownloadHandler {
        void patch_launchDownload(Object block);
    }

    private static final String DOWNLOAD_MEDIA_ACTION_CLASS_SUFFIX = "$downloadMedia$1";
    private static final String DEFAULT_MIME_TYPE = "image/jpeg";
    private static final String DEFAULT_EXTENSION = "jpg";
    private static final String REDDIT_PICTURES_FOLDER = Environment.DIRECTORY_PICTURES + "/Reddit";
    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 30000;
    private static final int BUFFER_SIZE = 8192;

    private static final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private static volatile boolean bypassNextDownload;
    @Nullable
    private static volatile Link shareSheetPost;

    private static volatile WeakReference<Activity> mediaViewerActivityRef = new WeakReference<>(null);

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Called when the media viewer is created.
     */
    public static void setMediaViewerActivity(Activity activity) {
        mediaViewerActivityRef = new WeakReference<>(activity);
    }

    /**
     * Injection point.
     */
    public static void setShareSheetLink(@Nullable Link link) {
        try {
            if (!Settings.DOWNLOAD_ALL_FROM_GALLERY.get()) {
                return;
            }

            if (link == null) {
                return;
            }

            List<Link> crossPosts = link.getCrossPostParentList();
            shareSheetPost = (crossPosts != null && !crossPosts.isEmpty())
                    ? crossPosts.get(0)
                    : link;
        } catch (Exception ex) {
            Logger.printException(() -> "setShareSheetLink failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @return If the dialog is shown instead of Reddit's download.
     */
    public static boolean onDownload(Object handler, Object block) {
        try {
            if (bypassNextDownload) {
                bypassNextDownload = false;
                return false;
            }

            if (!Settings.DOWNLOAD_ALL_FROM_GALLERY.get()) {
                return false;
            }

            if (block == null || !block.getClass().getName().endsWith(DOWNLOAD_MEDIA_ACTION_CLASS_SUFFIX)) {
                return false;
            }

            Link post = shareSheetPost;
            if (post == null) {
                return false;
            }

            PostGallery gallery = post.getGallery();
            if (gallery == null) {
                return false;
            }

            List<PostGalleryItem> rawItems = gallery.getItems();
            if (rawItems == null) {
                return false;
            }

            List<PostGalleryItem> items = new ArrayList<>();
            for (PostGalleryItem item : rawItems) {
                if (item != null) {
                    String url = item.getUrl();
                    if (url != null && !url.isEmpty()) {
                        items.add(item);
                    }
                }
            }

            if (items.size() < 2) {
                return false;
            }

            Activity mediaViewer = mediaViewerActivityRef.get();
            Activity activity = (mediaViewer != null && !mediaViewer.isFinishing() && !mediaViewer.isDestroyed())
                    ? mediaViewer
                    : Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return false;
            }

            Utils.runOnMainThread(() -> showDownloadDialog(activity, handler, block, post, items));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onDownload failure", ex);
            return false;
        }
    }

    private static void showDownloadDialog(
            Activity activity,
            Object handler,
            Object block,
            Link post,
            List<PostGalleryItem> items
    ) {
        try {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_download_all_dialog_title"),
                    str("morphe_download_all_dialog_message"),
                    null,
                    str("morphe_download_all_all", items.size()),
                    () -> downloadGallery(post, items),
                    () -> {},
                    str("morphe_download_all_this_one"),
                    () -> {
                        bypassNextDownload = true;
                        ((DownloadHandler) handler).patch_launchDownload(block);
                    },
                    true
            );

            Utils.showDialog(activity, dialogPair.first, true, null);
        } catch (Exception ex) {
            Logger.printException(() -> "showDownloadDialog failure", ex);
            try {
                bypassNextDownload = true;
                ((DownloadHandler) handler).patch_launchDownload(block);
            } catch (Exception launchEx) {
                Logger.printException(() -> "patch_launchDownload failure", launchEx);
            }
        }
    }

    private static void downloadGallery(Link post, List<PostGalleryItem> items) {
        if (!isDownloading.compareAndSet(false, true)) {
            return;
        }

        final int totalCount = items.size();
        Utils.showToastShort(str("morphe_download_all_started", totalCount));

        Utils.runOnBackgroundThread(() -> {
            int savedCount = 0;
            ContentResolver resolver = Utils.getContext().getContentResolver();
            try {
                for (int i = 0; i < totalCount; i++) {
                    final int itemNumber = i + 1;
                    PostGalleryItem item = items.get(i);
                    String url = item.getUrl();
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    if (url.startsWith("//")) {
                        url = "https:" + url;
                    }
                    if (url.contains("&amp;")) {
                        url = url.replace("&amp;", "&");
                    }

                    HttpURLConnection conn = null;
                    Uri itemUri = null;
                    try {
                        conn = openConnection(url);
                        final int responseCode = conn.getResponseCode();
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            throw new IOException("HTTP " + responseCode + " for " + url);
                        }

                        String contentType = conn.getContentType();
                        if (contentType != null) {
                            final int semicolon = contentType.indexOf(';');
                            if (semicolon != -1) {
                                contentType = contentType.substring(0, semicolon);
                            }
                            contentType = contentType.trim().toLowerCase(Locale.ROOT);
                        }

                        String mimeType = null;
                        if (contentType != null && !contentType.isEmpty()) {
                            mimeType = contentType;
                        } else {
                            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                    MimeTypeMap.getFileExtensionFromUrl(url));
                        }
                        if (mimeType == null || mimeType.isEmpty()) {
                            mimeType = DEFAULT_MIME_TYPE;
                        }

                        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                        if (ext == null || ext.isEmpty()) {
                            ext = DEFAULT_EXTENSION;
                        }

                        final boolean isVideo = mimeType.startsWith("video/");

                        Uri collectionUri = isVideo
                                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

                        ContentValues values = new ContentValues();
                        String postId = post.getId();
                        String displayName = "reddit_" + (postId != null ? postId : "") + "_" + itemNumber + "." + ext;
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, REDDIT_PICTURES_FOLDER);
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                        itemUri = DownloadLocationPatch.insertGalleryItem(resolver, collectionUri, values, post, url, isVideo);
                        if (itemUri == null) {
                            throw new IOException("Failed to insert gallery item for " + url);
                        }

                        try (InputStream in = conn.getInputStream();
                             OutputStream out = resolver.openOutputStream(itemUri)) {
                            if (out == null) {
                                throw new IOException("Failed to open output stream for " + itemUri);
                            }
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                        }

                        if (MediaStore.AUTHORITY.equals(itemUri.getAuthority())) {
                            ContentValues updateValues = new ContentValues();
                            updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            resolver.update(itemUri, updateValues, null, null);
                        }

                        savedCount++;
                    } catch (Exception ex) {
                        Logger.printException(() -> "Failed to download gallery item " + itemNumber, ex);
                        if (itemUri != null) {
                            try {
                                resolver.delete(itemUri, null, null);
                            } catch (Exception deleteEx) {
                                Logger.printDebug(() -> "Failed to delete item: " + deleteEx);
                            }
                        }
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                }
            } finally {
                isDownloading.set(false);
                final int finalSavedCount = savedCount;
                Utils.runOnMainThread(() -> {
                    if (finalSavedCount == totalCount) {
                        Utils.showToastShort(str("morphe_download_all_done", finalSavedCount));
                    } else if (finalSavedCount > 0) {
                        Utils.showToastShort(str("morphe_download_all_partial", finalSavedCount, totalCount));
                    } else {
                        Utils.showToastShort(str("morphe_download_all_failed"));
                    }
                });
            }
        });
    }

    private static HttpURLConnection openConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }
}
