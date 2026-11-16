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
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SharePostContentPatch {

    public interface LinkInterface {
        boolean patch_isSelf();
        @Nullable String patch_getTitle();
        @Nullable String patch_getSelftext();
        @Nullable String patch_getUrl();
        @Nullable String patch_getPostHint();
        @Nullable Object patch_getMedia();
        @Nullable Object patch_getGallery();
        @Nullable List<?> patch_getCrossPostParentList();
    }

    public interface LinkMediaInterface {
        @Nullable Object patch_getRedditVideo();
    }

    public interface RedditVideoInterface {
        @Nullable String patch_getDownloadUrl();
    }

    public interface PostGalleryInterface {
        @Nullable List<?> patch_getItems();
    }

    public interface PostGalleryItemInterface {
        @Nullable String patch_getUrl();
    }

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final String SHARE_CACHE_DIR = "internal_cache_share/morphe_share";
    private static final String FILE_PROVIDER_CLASS = "androidx.core.content.FileProvider";
    private static final String DEFAULT_EXTENSION = "bin";

    private static final AtomicBoolean isSharing = new AtomicBoolean(false);
    private static volatile @Nullable Object pendingLink;

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
    public static void setLink(@Nullable Object link) {
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
        Object rawLink = pendingLink;
        pendingLink = null;

        if (!Settings.SHARE_POST_CONTENT.get() || !(rawLink instanceof LinkInterface)) {
            return false;
        }

        Intent inner;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        } else {
            inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        }
        if (inner == null) {
            return false;
        }

        LinkInterface link = (LinkInterface) rawLink;
        List<?> crossPosts = link.patch_getCrossPostParentList();
        LinkInterface post = link;
        if (crossPosts != null && !crossPosts.isEmpty()) {
            Object firstCrossPost = crossPosts.get(0);
            if (firstCrossPost instanceof LinkInterface) {
                post = (LinkInterface) firstCrossPost;
            }
        }

        final boolean isSelf = post.patch_isSelf();
        String rawUrl = post.patch_getUrl();
        String url = rawUrl != null ? rawUrl.trim() : "";
        String host = getHost(url);

        String videoUrl = null;
        Object mediaObj = post.patch_getMedia();
        if (mediaObj instanceof LinkMediaInterface) {
            Object videoObj = ((LinkMediaInterface) mediaObj).patch_getRedditVideo();
            if (videoObj instanceof RedditVideoInterface) {
                String downloadUrl = ((RedditVideoInterface) videoObj).patch_getDownloadUrl();
                if (downloadUrl != null) {
                    downloadUrl = downloadUrl.trim();
                    if (!downloadUrl.isEmpty()) {
                        videoUrl = downloadUrl;
                    }
                }
            }
        }
        if (videoUrl != null) {
            return shareMedia(context, chooser, inner, Collections.singletonList(videoUrl));
        }

        List<String> galleryUrls = null;
        Object galleryObj = post.patch_getGallery();
        if (galleryObj instanceof PostGalleryInterface) {
            List<?> items = ((PostGalleryInterface) galleryObj).patch_getItems();
            if (items != null && !items.isEmpty()) {
                List<String> itemUrls = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof PostGalleryItemInterface) {
                        String itemUrl = ((PostGalleryItemInterface) item).patch_getUrl();
                        if (itemUrl != null) {
                            itemUrl = itemUrl.trim();
                            if (!itemUrl.isEmpty()) {
                                itemUrls.add(itemUrl);
                            }
                        }
                    }
                }
                if (!itemUrls.isEmpty()) {
                    galleryUrls = itemUrls;
                }
            }
        }
        if (galleryUrls != null) {
            return shareMedia(context, chooser, inner, galleryUrls);
        }

        String postHint = post.patch_getPostHint();
        if (!url.isEmpty() && ("image".equals(postHint) || "i.redd.it".equals(host))) {
            return shareMedia(context, chooser, inner, Collections.singletonList(url));
        }

        String selftext = post.patch_getSelftext();
        if (isSelf && selftext != null && !selftext.trim().isEmpty()) {
            String title = post.patch_getTitle();
            String fullText = (title != null && !title.isEmpty())
                    ? (title + "\n\n" + selftext)
                    : selftext;
            shareText(chooser, inner, fullText);
            return false;
        }

        if (!isSelf && !url.isEmpty() && !isRedditHost(host)) {
            shareText(chooser, inner, url);
            return false;
        }

        return false;
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
                File shareDir = new File(cacheDir, SHARE_CACHE_DIR);

                if (shareDir.exists()) {
                    File[] existingFiles = shareDir.listFiles();
                    if (existingFiles != null) {
                        for (File file : existingFiles) {
                            file.delete();
                        }
                    }
                } else {
                    shareDir.mkdirs();
                }

                List<File> downloadedFiles = new ArrayList<>();
                List<String> mimeTypes = new ArrayList<>();
                long totalBytes = 0;

                for (int i = 0; i < urls.size(); i++) {
                    String downloadUrl = urls.get(i);
                    if (downloadUrl.startsWith("//")) {
                        // Protocol-relative URL.
                        downloadUrl = "https:" + downloadUrl;
                    }
                    if (downloadUrl.contains("&amp;")) {
                        // Reddit returns HTML-escaped gallery URLs.
                        downloadUrl = downloadUrl.replace("&amp;", "&");
                    }

                    HttpURLConnection conn = openConnection(downloadUrl);
                    try {
                        final int responseCode = conn.getResponseCode();
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            throw new IOException("HTTP " + responseCode + " for " + downloadUrl);
                        }

                        String contentType = conn.getContentType();
                        if (contentType != null) {
                            final int semicolon = contentType.indexOf(';');
                            if (semicolon != -1) {
                                contentType = contentType.substring(0, semicolon);
                            }
                            contentType = contentType.trim().toLowerCase(Locale.ROOT);
                        }

                        String ext = getExtension(contentType, downloadUrl);
                        File destFile = new File(shareDir, "share_" + i + "." + ext);

                        try (InputStream in = conn.getInputStream();
                             FileOutputStream out = new FileOutputStream(destFile)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
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
                        mimeTypes.add(contentType != null && !contentType.isEmpty()
                                ? contentType
                                : getMimeTypeFromExtension(ext));
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
                            .appendPath("internal_cache_files")
                            .appendPath("morphe_share")
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

    private static HttpURLConnection openConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static String getExtension(@Nullable String contentType, String urlString) {
        if (contentType != null) {
            switch (contentType) {
                case "image/jpeg":
                    return "jpg";
                case "image/png":
                    return "png";
                case "image/gif":
                    return "gif";
                case "image/webp":
                    return "webp";
                case "video/mp4":
                    return "mp4";
                default:
                    break;
            }
        }
        String extFromPath = getExtensionFromUrlPath(urlString);
        if (extFromPath != null) {
            return extFromPath;
        }
        return DEFAULT_EXTENSION;
    }

    @Nullable
    private static String getExtensionFromUrlPath(String urlString) {
        Uri uri = Uri.parse(urlString);
        String path = uri.getPath();
        if (path != null) {
            final int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash != -1 ? path.substring(lastSlash + 1) : path;
            final int lastDot = filename.lastIndexOf('.');
            if (lastDot != -1 && lastDot < filename.length() - 1) {
                String ext = filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                if (!ext.isEmpty()) {
                    return ext;
                }
            }
        }
        return null;
    }

    private static String getMimeTypeFromExtension(String ext) {
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "mp4":
                return "video/mp4";
            default:
                return "application/octet-stream";
        }
    }

    @Nullable
    private static String getHost(String url) {
        if (url.isEmpty()) {
            return null;
        }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host != null) {
            return host.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isRedditHost(@Nullable String host) {
        if (host == null || host.isEmpty()) {
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
