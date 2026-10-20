/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3872
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ForceAnimationsPatch {

    public enum AnimationsMode {
        /**
         * The server decides.
         */
        DEFAULT(null),
        ALWAYS_ON("ANIMATION_DECISION_ON"),
        ALWAYS_OFF("ANIMATION_DECISION_OFF");

        /**
         * Name of the YouTube animation decision this mode uses.
         */
        @Nullable
        private final String decisionName;

        /**
         * The YouTube animation decision constant, looked up on first use.
         */
        @Nullable
        private Enum<?> decision;
        private boolean decisionLookedUp;

        AnimationsMode(@Nullable String decisionName) {
            this.decisionName = decisionName;
        }

        /**
         * @param anyDecision Any constant of the YouTube animation decision enum.
         * @return The decision of this mode, or null to keep the decision of the server.
         */
        @Nullable
        private synchronized Enum<?> getDecision(Enum<?> anyDecision) {
            if (decisionName != null && !decisionLookedUp) {
                decisionLookedUp = true;
                try {
                    for (Object constant : anyDecision.getDeclaringClass().getEnumConstants()) {
                        if (decisionName.equals(((Enum<?>) constant).name())) {
                            decision = (Enum<?>) constant;
                            break;
                        }
                    }

                    if (decision == null) {
                        Logger.printException(() -> "Animation decision not found: " + decisionName);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "getDecision failure", ex);
                }
            }

            return decision;
        }
    }

    /**
     * Injection point.
     * <p>
     * Decision of YouTube's adaptive animation hints for an animation category, such as the
     * watch transitions, the page transitions or the animated images. The server can throttle
     * or turn off each category, and then the app skips its animations.
     *
     * @param decision The animation decision enum constant.
     */
    public static Object getAnimationDecision(Object decision) {
        AnimationsMode mode = Settings.FORCE_ANIMATIONS.get();
        if (mode == AnimationsMode.DEFAULT || !(decision instanceof Enum<?> original)) {
            return decision;
        }

        Enum<?> forced = mode.getDecision(original);
        return forced != null ? forced : decision;
    }

    /**
     * Injection point.
     * <p>
     * A/B tests that make some animations, such as the page transitions,
     * use their animations without asking for the animation decision.
     */
    public static boolean useAnimationDecision(boolean original) {
        return original || Settings.FORCE_ANIMATIONS.get() != AnimationsMode.DEFAULT;
    }
}
