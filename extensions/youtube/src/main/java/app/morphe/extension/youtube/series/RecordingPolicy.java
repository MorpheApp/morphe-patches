/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

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
