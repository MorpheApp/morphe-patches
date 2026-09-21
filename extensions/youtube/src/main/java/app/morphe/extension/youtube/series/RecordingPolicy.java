package app.morphe.extension.youtube.series;

import java.util.Objects;

/** Consent belongs to one account context. Unknown identity and incognito always deny recording. */
final class RecordingPolicy {
    private String account;
    private boolean incognito, enabled;
    private long generation;

    void observe(String currentAccount, boolean privateSession, boolean recordingEnabled) {
        if (!Objects.equals(account, currentAccount)
                || incognito != privateSession
                || enabled != recordingEnabled) generation++;
        account = currentAccount;
        incognito = privateSession;
        enabled = recordingEnabled;
    }

    boolean allows(String consentAccount) {
        return enabled && account != null && !incognito && account.equals(consentAccount);
    }

    boolean canConsent() {
        return account != null && !incognito;
    }

    boolean canConsent(long expectedGeneration) {
        return generation == expectedGeneration && canConsent();
    }

    String account() {
        return account;
    }

    long generation() {
        return generation;
    }

    void invalidate() {
        generation++;
    }
}
