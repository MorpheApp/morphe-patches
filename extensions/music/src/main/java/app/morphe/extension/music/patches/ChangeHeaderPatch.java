/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.music.patches;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class ChangeHeaderPatch {

    public enum HeaderLogo {
        DEFAULT(null),
        MORPHE("morphe_header_dark"),
        CUSTOM("morphe_header_custom_dark");

        private final String drawableName;

        HeaderLogo(String drawableName) {
            this.drawableName = drawableName;
        }

        private Integer getDrawableId() {
            if (drawableName == null) {
                return null;
            }

            int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, drawableName);
            if (id == 0) {
                Logger.printException(() ->
                        "Header drawable not found: " + drawableName
                );
                Settings.HEADER_LOGO.resetToDefault();
                return null;
            }

            return id;
        }
    }

    /**
     * Injection point.
     */
    public static int getHeaderDrawableId(int original) {
        return Objects.requireNonNullElse(
                Settings.HEADER_LOGO.get().getDrawableId(),
                original
        );
    }
}
