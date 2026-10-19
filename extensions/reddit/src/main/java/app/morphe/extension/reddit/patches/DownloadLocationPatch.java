/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3870
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.LinkMedia;
import com.reddit.domain.model.RedditVideo;

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.DocumentTree;
import app.morphe.extension.reddit.utils.DownloadPathPattern;
import app.morphe.extension.reddit.utils.PickedFolderVideos;
import app.morphe.extension.reddit.utils.RedditAccounts;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Reddit saves videos with the download manager to the downloads folder,
 * and images with the media store to Pictures/Reddit.
 * This patch saves both to the same folder, with an optional subfolder and file name pattern,
 * which can be overridden per account.
 * <p>
 * Without storage permissions, the media store only allows images and videos in some shared folders.
 * Other folders are picked with the system folder picker, which grants a permission to write to them.
 */
@SuppressWarnings("unused")
public final class DownloadLocationPatch {

    /**
     * Interface to use obfuscated methods of the image download parameters.
     */
    public interface ImageDownloadParams {
        // Methods are added during patching.
        String patch_getUrl();

        @Nullable
        String patch_getSubreddit();

        @Nullable
        String patch_getAuthor();
    }

    public enum Option {
        FOLDER(Settings.DOWNLOAD_FOLDER, "folder"),
        /**
         * Subfolders and file name, such as "{subreddit}/{author}_{date}".
         * A pattern that ends with a separator keeps Reddit's file names.
         */
        PATH_PATTERN(Settings.DOWNLOAD_PATH_PATTERN, "pattern");

        final StringSetting setting;
        /**
         * Key in the per account JSON object.
         */
        final String accountKey;

        Option(StringSetting setting, String accountKey) {
            this.setting = setting;
            this.accountKey = accountKey;
        }
    }

    /**
     * Account key of the options used when logged out or incognito.
     * Usernames can't contain the asterisk, so this can't be a real account.
     */
    public static final String ANONYMOUS_ACCOUNT = "*anonymous";
    private static final String ANONYMOUS_TOKEN_VALUE = "anonymous";

    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_GIF = "gif";
    private static final String TYPE_VIDEO = "video";

    private static final int MAX_VIDEO_SOURCES = 500;

    private record Source(@Nullable String subreddit, @Nullable String author) {
    }

    /**
     * What is downloaded, for the tokens.
     *
     * @param original The file name of the media url, without an extension.
     */
    private record Media(@Nullable Source source, String type, @Nullable String original) {
    }

    private record VideoPost(Source source, boolean gif) {
    }

    /**
     * Video download url to the post it's from.
     */
    private static final Map<String, VideoPost> videoPosts = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, VideoPost> eldest) {
                    return size() > MAX_VIDEO_SOURCES;
                }
            });

    /**
     * Set when a video download starts, and cleared when its destination is set,
     * so a download that is not from a post never uses the tokens of an earlier post.
     */
    @Nullable
    private static volatile Media pendingVideo;

    /**
     * Reddit downloads and saves an image on one thread without suspending.
     */
    private static final ThreadLocal<Media> savingImage = new ThreadLocal<>();

    /**
     * Where a download is saved. Null values are what Reddit chose.
     */
    private record Destination(@Nullable String folder, @Nullable String subfolder, @Nullable String fileName) {
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isDefault() {
            return folder == null && subfolder == null && fileName == null;
        }

        boolean isTree() {
            return DocumentTree.isTree(folder);
        }

        String getFolder(String defaultFolder) {
            String base = folder != null ? folder : defaultFolder;
            if (subfolder == null) return base;

            // Relative paths of the media store end with a separator.
            return base.endsWith("/") ? base + subfolder : base + "/" + subfolder;
        }
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If any download location option is set. If none is, the hooks do nothing.
     */
    private static boolean isEnabled() {
        return !Settings.DOWNLOAD_FOLDER.get().isEmpty()
                || !Settings.DOWNLOAD_PATH_PATTERN.get().isEmpty()
                || !Settings.DOWNLOAD_ACCOUNTS.get().isEmpty();
    }

    /**
     * Injection point. Called when a post is created.
     */
    public static void onLinkCreated(Link link) {
        try {
            if (!isEnabled()) return;

            // Posts are created soon after the app starts.
            PickedFolderVideos.resumePendingVideos();

            LinkMedia media = link.getMedia();
            RedditVideo video = media == null ? null : media.getRedditVideo();
            String url = video == null ? null : video.getDownloadUrl();
            if (url != null && !url.isEmpty()) {
                videoPosts.put(url, new VideoPost(new Source(link.getSubreddit(), link.getAuthor()), video.isGif()));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLinkCreated failure", ex);
        }
    }

    /**
     * Injection point. Called on the thread that downloads and saves an image.
     */
    public static void onImageSave(ImageDownloadParams params) {
        try {
            if (!isEnabled()) return;

            String url = params.patch_getUrl();
            String path = Uri.parse(url).getPath();
            String type = path != null && path.toLowerCase(Locale.ROOT).endsWith(".gif")
                    ? TYPE_GIF
                    : TYPE_IMAGE;
            Source source = new Source(params.patch_getSubreddit(), params.patch_getAuthor());
            savingImage.set(new Media(source, type, getImageName(url)));
        } catch (Exception ex) {
            Logger.printException(() -> "onImageSave failure", ex);
        }
    }

    /**
     * Injection point. Called when a video download is enqueued, before its destination is set.
     */
    public static void onVideoDownload(@Nullable Uri uri) {
        try {
            if (!isEnabled()) return;

            String url = uri == null ? null : uri.toString();
            VideoPost post = url == null ? null : videoPosts.get(url);
            pendingVideo = new Media(
                    post == null ? null : post.source,
                    post != null && post.gif ? TYPE_GIF : TYPE_VIDEO,
                    getVideoName(uri)
            );
        } catch (Exception ex) {
            Logger.printException(() -> "onVideoDownload failure", ex);
        }
    }

    /**
     * Injection point. Replaces {@link DownloadManager.Request#setDestinationInExternalPublicDir(String, String)}
     * of video downloads.
     */
    public static DownloadManager.Request setDestinationInExternalPublicDir(
            DownloadManager.Request request, String dirType, String subPath) {
        try {
            Media media = pendingVideo;
            pendingVideo = null;
            if (!isEnabled()) {
                return request.setDestinationInExternalPublicDir(dirType, subPath);
            }

            Destination destination = getDestination(media, subPath);
            String fileName = destination.fileName != null ? destination.fileName : subPath;
            if (destination.isTree()) {
                return PickedFolderVideos.setDestination(request, destination.folder, destination.subfolder, fileName);
            }
            if (!destination.isDefault()) {
                String folder = destination.getFolder(dirType);
                final int separatorIndex = folder.indexOf('/');
                if (separatorIndex < 0) {
                    return request.setDestinationInExternalPublicDir(folder, fileName);
                }

                return request.setDestinationInExternalPublicDir(
                        folder.substring(0, separatorIndex),
                        folder.substring(separatorIndex + 1) + "/" + fileName
                );
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setDestinationInExternalPublicDir failure", ex);
            Utils.showToastLong(str("morphe_download_location_save_failed"));
        }

        return request.setDestinationInExternalPublicDir(dirType, subPath);
    }

    /**
     * Injection point. Replaces {@link DownloadManager#enqueue(DownloadManager.Request)} of video downloads.
     */
    public static long enqueue(DownloadManager downloadManager, DownloadManager.Request request) {
        return PickedFolderVideos.enqueue(downloadManager, request);
    }

    /**
     * Injection point. Replaces {@link ContentResolver#insert(Uri, ContentValues)} of image downloads.
     */
    @Nullable
    public static Uri insertImage(ContentResolver resolver, Uri uri, ContentValues values) {
        Media media = savingImage.get();
        savingImage.remove();
        return insert(resolver, uri, values, media != null ? media : new Media(null, TYPE_IMAGE, null));
    }

    /**
     * Injection point. Replaces {@link ContentResolver#insert(Uri, ContentValues)} of images
     * saved from the share sheet, which are not from a post.
     */
    @Nullable
    public static Uri insertSharedImage(ContentResolver resolver, Uri uri, ContentValues values) {
        return insert(resolver, uri, values, new Media(null, TYPE_IMAGE, null));
    }

    @Nullable
    private static Uri insert(ContentResolver resolver, Uri uri, ContentValues values, Media media) {
        // Relative paths require Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isEnabled()) {
            try {
                String displayName = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
                Destination destination = getDestination(media, displayName);
                if (destination.isTree()) {
                    String name = destination.fileName != null ? destination.fileName
                            : displayName != null ? displayName
                            : values.getAsString(MediaStore.MediaColumns.TITLE);
                    // Reddit writes the image to the returned file.
                    return DocumentTree.createFile(destination.folder, destination.subfolder,
                            name, values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
                }
                if (!destination.isDefault()) {
                    ContentValues newValues = new ContentValues(values);
                    Uri newUri = uri;

                    if (destination.folder != null || destination.subfolder != null) {
                        String defaultFolder = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
                        String folder = destination.getFolder(defaultFolder != null
                                ? defaultFolder
                                : Environment.DIRECTORY_PICTURES);
                        newValues.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);

                        if (folder.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
                            // The image collection only allows the DCIM and Pictures folders.
                            newUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                            newValues.remove(MediaStore.MediaColumns.DATE_TAKEN);
                        }
                    }

                    if (destination.fileName != null) {
                        // Without an extension, the media store adds one for the mime type.
                        newValues.put(MediaStore.MediaColumns.DISPLAY_NAME, destination.fileName);
                    }

                    return resolver.insert(newUri, newValues);
                }
            } catch (Exception ex) {
                // Saving where Reddit chose is better than failing the download.
                Logger.printException(() -> "insert failure", ex);
                Utils.showToastLong(str("morphe_download_location_save_failed"));
            }
        }

        return resolver.insert(uri, values);
    }

    private static Destination getDestination(@Nullable Media media, @Nullable String originalFileName) {
        String account = getActiveAccount();
        return getDestination(getOptionValue(Option.FOLDER, account), getOptionValue(Option.PATH_PATTERN, account),
                account, media, originalFileName, true);
    }

    /**
     * @param originalFileName The file name Reddit chose, which can be null.
     * @param notify           If invalid or unavailable folders show a toast.
     */
    private static Destination getDestination(String folderSetting, String pattern, String account,
                                              @Nullable Media media, @Nullable String originalFileName,
                                              boolean notify) {
        String folder = getFolder(folderSetting.trim(), notify);
        DownloadPathPattern.Expanded expanded = DownloadPathPattern.expand(
                pattern.trim(), getTokenValues(account, media));
        String subfolder = expanded.subfolder();
        String fileName = expanded.fileName();

        if (!fileName.isEmpty() && originalFileName != null) {
            final int extensionIndex = originalFileName.lastIndexOf('.');
            if (extensionIndex > 0) {
                fileName += originalFileName.substring(extensionIndex);
            }
        }

        return new Destination(
                folder,
                subfolder.isEmpty() ? null : subfolder,
                fileName.isEmpty() ? null : fileName
        );
    }

    /**
     * @param folder  The folder setting, which can be empty or invalid.
     * @param pattern The subfolder and file name pattern, which can be empty.
     * @return An example path of a downloaded image or video, for the settings.
     */
    public static String getExamplePath(String folder, String pattern, String account, boolean video) {
        Media media = new Media(new Source("pics", "example_user"), video ? TYPE_VIDEO : TYPE_IMAGE, "abc123");
        String redditFileName = DownloadPathPattern.getRedditFileName(LocalDateTime.now()) + (video ? ".mp4" : ".jpg");
        Destination destination = getDestination(folder, pattern, account, media, redditFileName, false);

        StringBuilder path = new StringBuilder(destination.folder == null
                ? (video ? Environment.DIRECTORY_DOWNLOADS : Environment.DIRECTORY_PICTURES + "/Reddit")
                : destination.isTree() ? DocumentTree.getLabel(destination.folder)
                : destination.folder);
        if (destination.subfolder != null) {
            path.append('/').append(destination.subfolder);
        }
        return path.append('/')
                .append(destination.fileName != null ? destination.fileName : redditFileName)
                .toString();
    }

    /**
     * Key in the per account JSON object, for if the account options are used.
     */
    private static final String ACCOUNT_ENABLED_KEY = "enabled";

    /**
     * @return If the account uses its own options instead of the options of all accounts.
     */
    public static boolean isAccountEnabled(String account) {
        JSONObject accountOptions = getAccountsOptions().optJSONObject(account);
        return accountOptions != null && accountOptions.optBoolean(ACCOUNT_ENABLED_KEY);
    }

    /**
     * @return The logged in account, or {@link #ANONYMOUS_ACCOUNT} if logged out or incognito.
     */
    public static String getActiveAccount() {
        String account = RedditAccounts.getActive();
        return account != null ? account : ANONYMOUS_ACCOUNT;
    }

    /**
     * @return The accounts that have options, including accounts that are no longer logged in.
     */
    public static List<String> getAccountsWithOptions() {
        List<String> accounts = new ArrayList<>();
        Iterator<String> keys = getAccountsOptions().keys();
        while (keys.hasNext()) {
            accounts.add(keys.next());
        }
        return accounts;
    }

    public static boolean hasAccountOptions(String account) {
        return getAccountsOptions().has(account);
    }

    /**
     * Options of a disabled account are kept, so they are restored when it's enabled again.
     *
     * @param folder  Empty to use the folder of all accounts.
     * @param pattern Empty to use the pattern of all accounts.
     */
    public static void setAccountOptions(String account, boolean enabled, String folder, String pattern) {
        try {
            JSONObject accountOptions = new JSONObject();
            if (enabled) accountOptions.put(ACCOUNT_ENABLED_KEY, true);
            if (!folder.isEmpty()) accountOptions.put(Option.FOLDER.accountKey, folder);
            if (!pattern.isEmpty()) accountOptions.put(Option.PATH_PATTERN.accountKey, pattern);

            updateAccountsOptions(accountsOptions -> {
                if (accountOptions.length() == 0) {
                    accountsOptions.remove(account);
                } else {
                    accountsOptions.put(account, accountOptions);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setAccountOptions failure", ex);
        }
    }

    public static void deleteAccountOptions(String account) {
        updateAccountsOptions(accountsOptions -> accountsOptions.remove(account));
    }

    /**
     * @return The value of an enabled account, or the value of all accounts if the account has none.
     */
    public static String getOptionValue(Option option, String account) {
        if (isAccountEnabled(account)) {
            String value = getAccountOptionValue(option, account);
            if (!value.isEmpty()) return value;
        }

        return option.setting.get().trim();
    }

    public static String getAccountOptionValue(Option option, String account) {
        JSONObject accountOptions = getAccountsOptions().optJSONObject(account);
        return accountOptions == null ? "" : accountOptions.optString(option.accountKey).trim();
    }

    private interface AccountsOptionsUpdate {
        void update(JSONObject accountsOptions) throws Exception;
    }

    private static void updateAccountsOptions(AccountsOptionsUpdate update) {
        try {
            JSONObject accountsOptions = parseAccountsOptions();
            // Saving over options that can't be parsed would lose them.
            if (accountsOptions == null) return;

            update.update(accountsOptions);
            Settings.DOWNLOAD_ACCOUNTS.save(accountsOptions.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "updateAccountsOptions failure", ex);
        }
    }

    private static JSONObject getAccountsOptions() {
        JSONObject accountsOptions = parseAccountsOptions();
        return accountsOptions != null ? accountsOptions : new JSONObject();
    }

    /**
     * @return The options, or null if they can't be parsed.
     */
    @Nullable
    private static JSONObject parseAccountsOptions() {
        String json = Settings.DOWNLOAD_ACCOUNTS.get();
        if (json.isEmpty()) return new JSONObject();

        try {
            return new JSONObject(json);
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid account download options: " + json, ex);
            return null;
        }
    }

    private static DownloadPathPattern.Values getTokenValues(String account, @Nullable Media media) {
        Source source = media == null ? null : media.source;
        return new DownloadPathPattern.Values(
                ANONYMOUS_ACCOUNT.equals(account) ? ANONYMOUS_TOKEN_VALUE : account,
                source == null ? null : source.subreddit,
                source == null ? null : source.author,
                media == null ? null : media.type,
                media == null ? null : media.original,
                LocalDateTime.now()
        );
    }

    /**
     * @return The video id of a Reddit video url, such as v.redd.it/[id]/... or packaged-media.redd.it/[id]/...
     */
    @Nullable
    private static String getVideoName(@Nullable Uri uri) {
        if (uri == null) return null;
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? null : segments.get(0);
    }

    /**
     * @return The file name of an image url without an extension, such as i.redd.it/[name].jpg
     */
    @Nullable
    private static String getImageName(@Nullable String url) {
        if (url == null) return null;
        String name = Uri.parse(url).getLastPathSegment();
        if (name == null) return null;

        final int extensionIndex = name.lastIndexOf('.');
        return extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
    }

    @Nullable
    private static String getFolder(String setting, boolean notify) {
        if (setting.isEmpty()) return null;

        if (DocumentTree.isTree(setting)) {
            if (!notify || DocumentTree.hasPermission(setting)) return setting;
            Logger.printInfo(() -> "No permission for download folder: " + setting);
            Utils.showToastLong(str("morphe_download_location_folder_unavailable"));
            return null;
        }

        String folder = DownloadPathPattern.normalizeFolder(setting);
        if (folder == null && notify) {
            Logger.printInfo(() -> "Invalid download folder: " + setting);
            Utils.showToastLong(str("morphe_download_location_invalid_folder"));
        }
        return folder;
    }
}
