/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4916
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.LinkMedia;
import com.reddit.domain.model.RedditVideo;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.PostMedia;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SharePostContentPatch {

    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    /**
     * Reddit FileProvider cache-path: the cache directory "internal_cache_share" has the uri path "internal_cache_files".
     */
    private static final String PROVIDER_CACHE_DIR = "internal_cache_share";
    private static final String PROVIDER_CACHE_PATH_NAME = "internal_cache_files";
    private static final String SHARE_DIR = "morphe_share";
    private static final String FILE_PROVIDER_CLASS = "androidx.core.content.FileProvider";

    private static final AtomicBoolean isSharing = new AtomicBoolean(false);
    /**
     * Post of the share sheet, loaded when the share subject is built.
     */
    @Nullable
    private static volatile Link pendingLink;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void clearLink() {
        pendingLink = null;
    }

    /**
     * Injection point.
     */
    public static void setLink(@Nullable Link link) {
        pendingLink = link;
    }

    /**
     * Injection point.
     */
    public static boolean onShare(Context context, Intent chooser) {
        try {
            return handleShare(context, chooser);
        } catch (Throwable ex) {
            Logger.printException(() -> "onShare failure", ex);
            return false;
        }
    }

    private static boolean handleShare(Context context, Intent chooser) {
        Link link = pendingLink;
        pendingLink = null;

        if (!Settings.SHARE_POST_CONTENT.get() || link == null) {
            return false;
        }

        Intent inner = getTargetIntent(chooser);
        if (inner == null) {
            return false;
        }

        // A crosspost shares the content of the original post.
        Link post = PostMedia.getContentPost(link);

        LinkMedia media = post.getMedia();
        if (media != null) {
            RedditVideo video = media.getRedditVideo();
            if (video != null) {
                String videoUrl = trimToNull(video.getDownloadUrl());
                if (videoUrl != null) {
                    return shareMedia(context, chooser, inner, Collections.singletonList(videoUrl));
                }
            }
        }

        List<String> galleryUrls = PostMedia.getGalleryUrls(post);
        if (!galleryUrls.isEmpty()) {
            return shareMedia(context, chooser, inner, galleryUrls);
        }

        String url = trimToNull(post.getUrl());
        String host = url == null ? null : getHost(url);
        if (url != null && ("image".equals(post.getPostHint()) || "i.redd.it".equals(host))) {
            return shareMedia(context, chooser, inner, Collections.singletonList(url));
        }

        final boolean isSelf = post.isSelf();
        String selftext = post.getSelftext();
        if (isSelf && trimToNull(selftext) != null) {
            String title = post.getTitle();
            shareText(chooser, inner, title == null || title.isEmpty()
                    ? selftext
                    : title + "\n\n" + selftext);
        } else if (!isSelf && url != null && !isRedditHost(host)) {
            shareText(chooser, inner, url);
        }

        // Text is changed in place and Reddit opens the share target as usual.
        return false;
    }

    /**
     * @return The share intent inside the chooser intent.
     */
    @Nullable
    @SuppressWarnings("deprecation")
    private static Intent getTargetIntent(Intent chooser) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        // The replacement is only available on Android 13+.
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    @Nullable
    private static String trimToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void shareText(Intent chooser, Intent inner, String text) {
        String action = inner.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SENDTO.equals(action)) {
            inner.putExtra(Intent.EXTRA_TEXT, text);
            chooser.putExtra(Intent.EXTRA_INTENT, inner);
        }
    }

    private static boolean shareMedia(
            Context context,
            Intent chooser,
            Intent inner,
            List<String> urls
    ) {
        if (!Intent.ACTION_SEND.equals(inner.getAction())) {
            return false;
        }
        if (!isSharing.compareAndSet(false, true)) {
            return true;
        }

        Utils.showToastShort(str("morphe_share_post_content_preparing"));
        final Intent untouchedChooser = new Intent(chooser);

        Utils.runOnBackgroundThread(() -> {
            try {
                Context appContext = Utils.getContext();
                File cacheDir = appContext.getCacheDir();
                File shareDir = new File(new File(cacheDir, PROVIDER_CACHE_DIR), SHARE_DIR);

                if (shareDir.exists()) {
                    File[] existingFiles = shareDir.listFiles();
                    if (existingFiles != null) {
                        for (File file : existingFiles) {
                            // A file that is not deleted is overwritten or left unused.
                            if (!file.delete()) {
                                Logger.printDebug(() -> "Could not delete old share file: " + file.getName());
                            }
                        }
                    }
                } else if (!shareDir.mkdirs()) {
                    throw new IOException("Could not create share directory: " + shareDir);
                }

                List<File> downloadedFiles = new ArrayList<>();
                List<String> mimeTypes = new ArrayList<>();
                long totalBytes = 0;

                for (int i = 0; i < urls.size(); i++) {
                    String downloadUrl = PostMedia.toDownloadUrl(urls.get(i));
                    HttpURLConnection conn = PostMedia.openConnection(downloadUrl);
                    try {
                        String contentType = PostMedia.getContentType(conn);
                        String ext = PostMedia.getExtension(contentType, downloadUrl);
                        File destFile = new File(shareDir, "share_" + i + "." + ext);

                        try (InputStream in = conn.getInputStream();
                             FileOutputStream out = new FileOutputStream(destFile)) {
                            byte[] buffer = new byte[PostMedia.BUFFER_SIZE];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                totalBytes += bytesRead;
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    throw new IOException("Download size exceeded " + MAX_TOTAL_BYTES + " bytes");
                                }
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                        }

                        downloadedFiles.add(destFile);
                        mimeTypes.add(contentType != null
                                ? contentType
                                : PostMedia.getMimeTypeFromExtension(ext));
                    } finally {
                        conn.disconnect();
                    }
                }

                if (downloadedFiles.isEmpty()) {
                    throw new IOException("No files downloaded");
                }

                String authority = getFileProviderAuthority(appContext);
                if (authority == null) {
                    throw new IOException("FileProvider authority not found");
                }

                List<Uri> uris = new ArrayList<>();
                for (File file : downloadedFiles) {
                    Uri uri = new Uri.Builder()
                            .scheme("content")
                            .authority(authority)
                            .appendPath(PROVIDER_CACHE_PATH_NAME)
                            .appendPath(SHARE_DIR)
                            .appendPath(file.getName())
                            .build();
                    uris.add(uri);
                }

                Intent newInner = new Intent(inner);
                newInner.removeExtra(Intent.EXTRA_TEXT);

                String sendMimeType;
                if (uris.size() == 1) {
                    newInner.setAction(Intent.ACTION_SEND);
                    newInner.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    sendMimeType = mimeTypes.get(0);
                } else {
                    newInner.setAction(Intent.ACTION_SEND_MULTIPLE);
                    newInner.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
                    sendMimeType = "image/*";
                }
                newInner.setType(sendMimeType);

                ClipData clipData = ClipData.newUri(appContext.getContentResolver(), "media", uris.get(0));
                for (int i = 1; i < uris.size(); i++) {
                    clipData.addItem(new ClipData.Item(uris.get(i)));
                }
                newInner.setClipData(clipData);
                newInner.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Utils.runOnMainThread(() -> {
                    try {
                        chooser.putExtra(Intent.EXTRA_INTENT, newInner);
                        chooser.setClipData(clipData);
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        if (!(context instanceof Activity)) {
                            newInner.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }

                        String pkg = newInner.getPackage();
                        if (pkg == null && newInner.getComponent() != null) {
                            pkg = newInner.getComponent().getPackageName();
                        }

                        if (pkg != null && !pkg.isEmpty()) {
                            try {
                                context.startActivity(newInner);
                            } catch (ActivityNotFoundException ex) {
                                context.startActivity(chooser);
                            }
                        } else {
                            context.startActivity(chooser);
                        }
                    } catch (Throwable ex) {
                        Logger.printException(() -> "startActivity failure", ex);
                        handleShareFailure(context, untouchedChooser);
                    }
                });
            } catch (Throwable ex) {
                Logger.printException(() -> "downloadAndShare failure", ex);
                handleShareFailure(context, untouchedChooser);
            } finally {
                isSharing.set(false);
            }
        });

        return true;
    }

    private static void handleShareFailure(Context context, Intent fallbackChooser) {
        Utils.showToastShort(str("morphe_share_post_content_failed"));
        Utils.runOnMainThread(() -> {
            try {
                if (!(context instanceof Activity)) {
                    fallbackChooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(fallbackChooser);
            } catch (Throwable ex) {
                Logger.printException(() -> "handleShareFailure startActivity failure", ex);
            }
        });
    }

    @Nullable
    private static String getHost(String url) {
        String host = Uri.parse(url).getHost();
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static boolean isRedditHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        return host.equals("reddit.com") || host.endsWith(".reddit.com")
                || host.equals("redd.it") || host.endsWith(".redd.it");
    }

    @Nullable
    private static String getFileProviderAuthority(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(
                        context.getPackageName(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS)
                );
            } else {
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PROVIDERS);
            }
            if (info.providers != null) {
                for (ProviderInfo provider : info.providers) {
                    if (FILE_PROVIDER_CLASS.equals(provider.name)) {
                        String authority = provider.authority;
                        if (authority != null && authority.contains(";")) {
                            authority = authority.split(";")[0];
                        }
                        return authority;
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "getFileProviderAuthority failure", ex);
        }
        return null;
    }
}
