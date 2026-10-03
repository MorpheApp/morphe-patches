package app.morphe.extension.youtube.series;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Reads the native current-account provider; never reads names, tokens or request headers. */
public final class RecordingPrivacy {
    public interface Source {
        Identity seriesTrackerIdentity();
    }

    public static final class Identity {
        final String key;
        final boolean incognito;

        public Identity(String key, boolean incognito) {
            this.key = key;
            this.incognito = incognito;
        }
    }

    private static volatile Source source;
    private static final RecordingPolicy policy = new RecordingPolicy();
    private static SharedPreferences preferences;
    private static String consentAccount, syncAccount;
    private static String salt;

    /** Called from both native current-account provider implementations. */
    public static void attach(Source provider) {
        source = provider;
    }

    private static void refresh() {
        Context context = Utils.getContext();
        if (context == null) {
            policy.observe(null, false, false);
            return;
        }
        if (preferences == null) {
            preferences =
                    context.getSharedPreferences("series_tracker_privacy", Context.MODE_PRIVATE);
            consentAccount = preferences.getString("consent_account", null);
            syncAccount = preferences.getString("sync_account", null);
            salt = preferences.getString("salt", UUID.randomUUID().toString());
        }
        String account = null;
        boolean incognito = false;
        try {
            Source provider = source;
            Identity identity = provider == null ? null : provider.seriesTrackerIdentity();
            if (identity != null && identity.key != null) {
                incognito = identity.incognito;
                // Only the digest of the opaque identity is persisted for consent matching.
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        (salt + "\0" + identity.key)
                                                .getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(64);
                for (byte b : digest)
                    hex.append(Character.forDigit((b & 255) >>> 4, 16))
                            .append(Character.forDigit(b & 15, 16));
                account = hex.toString();
            }
        } catch (Exception | LinkageError unavailable) {
            // Identity unavailable: fail closed, without logging account information.
        }
        policy.observe(account, incognito, Settings.SERIES_TRACKER_RECORD_PROGRESS.get());
    }

    static synchronized boolean allowsRecording() {
        refresh();
        return policy.allows(consentAccount);
    }

    static synchronized long generation() {
        refresh();
        return policy.generation();
    }

    static synchronized boolean accepts(long generation) {
        refresh();
        return policy.generation() == generation && policy.allows(consentAccount);
    }

    /** Explicit one-time position saving does not enable automatic recording. */
    static synchronized boolean acceptsManualPosition(long generation) {
        refresh();
        return policy.canConsent(generation);
    }

    static synchronized boolean canEnable() {
        refresh();
        return policy.canConsent();
    }

    static synchronized boolean enable(long expectedGeneration) {
        refresh();
        if (!policy.canConsent(expectedGeneration)) return false;
        String account = policy.account();
        // Do not enable until the consent binding is durable.
        if (!preferences
                .edit()
                .putString("consent_account", account)
                .putString("salt", salt)
                .commit()) return false;
        consentAccount = account;
        policy.invalidate();
        ((Setting<Boolean>) Settings.SERIES_TRACKER_RECORD_PROGRESS).save(true);
        refresh();
        return policy.allows(consentAccount);
    }

    static synchronized void disable() {
        ((Setting<Boolean>) Settings.SERIES_TRACKER_RECORD_PROGRESS).save(false);
        policy.invalidate();
        refresh();
    }

    static synchronized boolean allowsSync() {
        refresh();
        return Settings.SERIES_TRACKER_YOUTUBE_PROGRESS.get()
                && policy.canConsent()
                && policy.account().equals(syncAccount);
    }

    /** Bind a native request to its originating identity, not just the current UI account. */
    static synchronized long requestGeneration(Identity origin) {
        if (!allowsSync() || origin == null || origin.incognito) return -1;
        try {
            Identity current = source.seriesTrackerIdentity();
            return current != null
                            && !current.incognito
                            && current.key != null
                            && current.key.equals(origin.key)
                    ? policy.generation()
                    : -1;
        } catch (Exception | LinkageError unavailable) {
            return -1;
        }
    }

    static synchronized String syncScope(long generation) {
        return acceptsSync(generation) ? policy.account() : "";
    }

    static synchronized boolean acceptsSync(long expectedGeneration) {
        return generation() == expectedGeneration && allowsSync();
    }

    static synchronized boolean enableSync(long expectedGeneration) {
        refresh();
        if (!policy.canConsent(expectedGeneration)) return false;
        String account = policy.account();
        if (!preferences.edit().putString("sync_account", account).putString("salt", salt).commit())
            return false;
        syncAccount = account;
        policy.invalidate();
        Settings.SERIES_TRACKER_YOUTUBE_PROGRESS.save(true);
        return allowsSync();
    }

    static synchronized void disableSync() {
        Settings.SERIES_TRACKER_YOUTUBE_PROGRESS.save(false);
        policy.invalidate();
    }

    private RecordingPrivacy() {}
}
