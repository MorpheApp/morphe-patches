/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5193
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.PostMedia;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("unused")
public final class DownloadAllFromGalleryPatch {

    /**
     * Interface to use obfuscated methods of the share sheet action handler.
     */
    public interface ShareActionHandler {
        // Methods are added during patching.
        void patch_launchDownload(Object block);

        /**
         * @return The id of the shared post, or null if the share sheet is not for a post.
         */
        @Nullable
        String patch_getPostId();
    }

    /**
     * Folder of the images saved by Reddit.
     */
    private static final String REDDIT_PICTURES_FOLDER = Environment.DIRECTORY_PICTURES + "/Reddit";

    /**
     * @param id   ID of the post of the share sheet.
     * @param post Post with the content, which is the original post of a crosspost.
     */
    private record SharedPost(String id, Link post) {
    }

    /**
     * Post of the last share sheet, set when its preview is built.
     */
    @Nullable
    private static volatile SharedPost sharedPost;

    /**
     * The media viewer is used only while it is resumed; a share sheet does not pause it.
     */
    private static volatile WeakReference<Activity> mediaViewerActivityRef = new WeakReference<>(null);

    private static final Application.ActivityLifecycleCallbacks ACTIVITY_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityResumed(@NonNull Activity a) {
            mediaViewerActivityRef = new WeakReference<>(a);
        }

        @Override
        public void onActivityPaused(@NonNull Activity a) {
            mediaViewerActivityRef = new WeakReference<>(null);
        }

        @Override
        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}

        @Override
        public void onActivityStarted(@NonNull Activity a) {}

        @Override
        public void onActivityStopped(@NonNull Activity a) {}

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}

        @Override
        public void onActivityDestroyed(@NonNull Activity a) {}
    };

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Called when the media viewer is created.
     * The media viewer is used only while it is resumed; a share sheet does not pause it.
     */
    @SuppressLint("NewApi")
    public static void setMediaViewerActivity(Activity activity) {
        try {
            mediaViewerActivityRef = new WeakReference<>(activity);
            activity.registerActivityLifecycleCallbacks(ACTIVITY_LIFECYCLE_CALLBACKS);
        } catch (Exception ex) {
            Logger.printException(() -> "setMediaViewerActivity failure", ex);
        }
    }

    /**
     * Injection point. Called when the share sheet has loaded its post.
     */
    public static void setShareSheetLink(@Nullable Link link) {
        try {
            if (!Settings.DOWNLOAD_ALL_FROM_GALLERY.get()) return;

            sharedPost = link == null
                    ? null
                    : new SharedPost(link.getId(), PostMedia.getContentPost(link));
        } catch (Exception ex) {
            Logger.printException(() -> "setShareSheetLink failure", ex);
        }
    }

    /**
     * Injection point. Replaces the launch of the download of the share sheet Download action.
     */
    public static void launchDownload(ShareActionHandler handler, Object block) {
        try {
            Link post = getGalleryPost(handler);
            if (post != null) {
                Utils.runOnMainThread(() -> showDownloadDialog(handler, block, post));
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "launchDownload failure", ex);
        }

        handler.patch_launchDownload(block);
    }

    /**
     * @return The post with more than one gallery item, if the share sheet is for one.
     */
    @Nullable
    private static Link getGalleryPost(ShareActionHandler handler) {
        if (!Settings.DOWNLOAD_ALL_FROM_GALLERY.get()) return null;

        SharedPost shared = sharedPost;
        // The preview of a share sheet is built after the post is loaded,
        // so this can still be the post of an earlier share sheet.
        if (shared == null || !shared.id.equals(handler.patch_getPostId())) return null;

        return PostMedia.getGalleryUrls(shared.post).size() > 1 ? shared.post : null;
    }

    private static void showDownloadDialog(ShareActionHandler handler, Object block, Link post) {
        try {
            Activity activity = getActivity();
            if (activity != null) {
                List<String> urls = PostMedia.getGalleryUrls(post);
                CustomDialog.create(
                        activity,
                        str("morphe_download_all_dialog_title"),
                        str("morphe_download_all_dialog_message"),
                        null,
                        str("morphe_download_all_all", urls.size()),
                        () -> downloadGallery(post, urls),
                        () -> {},
                        str("morphe_download_all_this_one"),
                        () -> handler.patch_launchDownload(block),
                        true
                ).first.show();
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showDownloadDialog failure", ex);
        }

        handler.patch_launchDownload(block);
    }

    /**
     * @return The activity in front, which is the media viewer if it's open.
     */
    @Nullable
    private static Activity getActivity() {
        Activity mediaViewer = mediaViewerActivityRef.get();
        if (isActive(mediaViewer)) {
            return mediaViewer;
        }

        Activity activity = Utils.getActivity();
        return isActive(activity) ? activity : null;
    }

    private static boolean isActive(@Nullable Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void downloadGallery(Link post, List<String> urls) {
        final int totalCount = urls.size();
        Utils.showToastShort(str("morphe_download_all_started", totalCount));

        Utils.runOnBackgroundThread(() -> {
            ContentResolver resolver = Utils.getContext().getContentResolver();
            int savedCount = 0;
            for (int i = 0; i < totalCount; i++) {
                final int itemNumber = i + 1;
                try {
                    saveItem(resolver, post, urls.get(i), itemNumber);
                    savedCount++;
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not save gallery item " + itemNumber, ex);
                }
            }

            if (savedCount == totalCount) {
                Utils.showToastShort(str("morphe_download_all_done", savedCount));
            } else if (savedCount > 0) {
                Utils.showToastShort(str("morphe_download_all_partial", savedCount, totalCount));
            } else {
                Utils.showToastShort(str("morphe_download_all_failed"));
            }
        });
    }

    /**
     * Saves an item where Reddit saves images, or where the Download location patch saves them.
     */
    private static void saveItem(ContentResolver resolver, Link post, String url, int itemNumber) throws IOException {
        HttpURLConnection conn = PostMedia.openConnection(url);
        Uri itemUri;
        boolean saved = false;
        try {
            String contentType = PostMedia.getContentType(conn);
            String extension = PostMedia.getExtension(contentType, url);
            String mimeType = contentType != null ? contentType : PostMedia.getMimeTypeFromExtension(extension);
            final boolean isVideo = mimeType.startsWith("video/");
            if (!isVideo && !mimeType.startsWith("image/")) {
                throw new IOException("Not an image or video: " + mimeType + " for " + url);
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "reddit_" + post.getId() + "_" + itemNumber + "." + extension);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, REDDIT_PICTURES_FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = isVideo
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            itemUri = DownloadLocationPatch.insertGalleryItem(resolver, collection, values, post, url);
            if (itemUri == null) {
                throw new IOException("Could not create the file for " + url);
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) {
                    throw new IOException("Could not open " + itemUri);
                }
                byte[] buffer = new byte[PostMedia.BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // A file of a folder picked with the system folder picker is not in the media store.
            if (isInMediaStore(itemUri)) {
                ContentValues published = new ContentValues();
                published.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(itemUri, published, null, null);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isInMediaStore(Uri uri) {
        return MediaStore.AUTHORITY.equals(uri.getAuthority());
    }

    private static void deleteItem(ContentResolver resolver, Uri itemUri) {
        try {
            if (isInMediaStore(itemUri)) {
                resolver.delete(itemUri, null, null);
            } else {
                DocumentsContract.deleteDocument(resolver, itemUri);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not delete unfinished file: " + itemUri, ex);
        }
    }
}
