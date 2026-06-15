package app.morphe.extension.music.sponsorblock.objects;

import static app.morphe.extension.shared.StringRef.sf;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.StringRef;

public enum CategoryBehaviour {
    SKIP_AUTOMATICALLY("skip", 2, true, sf("morphe_music_sb_skip_automatically")),
    IGNORE("ignore", -1, false, sf("morphe_music_sb_skip_ignore"));

    @NonNull
    public final String morpheKeyValue;
    public final int desktopKeyValue;
    public final boolean skipAutomatically;
    @NonNull
    public final StringRef description;

    CategoryBehaviour(String morpheKeyValue, int desktopKeyValue, boolean skipAutomatically, StringRef description) {
        this.morpheKeyValue = Objects.requireNonNull(morpheKeyValue);
        this.desktopKeyValue = desktopKeyValue;
        this.skipAutomatically = skipAutomatically;
        this.description = Objects.requireNonNull(description);
    }

    @Nullable
    public static CategoryBehaviour byMorpheKeyValue(@NonNull String keyValue) {
        for (CategoryBehaviour b : values()) {
            if (b.morpheKeyValue.equals(keyValue)) return b;
        }
        return null;
    }

    @Nullable
    public static CategoryBehaviour byDesktopKeyValue(int desktopKeyValue) {
        for (CategoryBehaviour b : values()) {
            if (b.desktopKeyValue == desktopKeyValue) return b;
        }
        return null;
    }
}
