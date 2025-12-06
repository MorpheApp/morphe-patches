package app.morphe.extension.shared.checks;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

abstract class Check {
    private static final int NUMBER_OF_TIMES_TO_IGNORE_WARNING_BEFORE_DISABLING = 2;

    /**
     * @return If the check conclusively passed or failed. A null value indicates it neither passed nor failed.
     */
    @Nullable
    protected abstract Boolean check();

    protected abstract String failureReason();

    /**
     * Specifies a sorting order for displaying the checks that failed.
     * A lower value indicates to show first before other checks.
     */
    public abstract int uiSortingValue();

    /**
     * For debugging and development only.
     * Forces all checks to be performed and the check failed dialog to be shown.
     * Can be enabled by importing settings text with {@link BaseSettings#CHECK_ENVIRONMENT_WARNINGS_ISSUED}
     * set to -1.
     */
    static boolean debugAlwaysShowWarning() {
        final boolean alwaysShowWarning = BaseSettings.CHECK_ENVIRONMENT_WARNINGS_ISSUED.get() < 0;
        if (alwaysShowWarning) {
            Logger.printInfo(() -> "Debug forcing environment check warning to show");
        }

        return alwaysShowWarning;
    }

    static boolean shouldRun() {
        return BaseSettings.CHECK_ENVIRONMENT_WARNINGS_ISSUED.get()
                < NUMBER_OF_TIMES_TO_IGNORE_WARNING_BEFORE_DISABLING;
    }

    static void disableForever() {
        Logger.printInfo(() -> "Environment checks disabled forever");

        BaseSettings.CHECK_ENVIRONMENT_WARNINGS_ISSUED.save(Integer.MAX_VALUE);
    }
}
