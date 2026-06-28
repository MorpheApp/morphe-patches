/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.shared;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class PipDismissHelper {

    private PipDismissHelper() {}

    /**
     * Dismisses {@code dialog} when the player enters Picture-in-Picture mode,
     * and cleans up the observer when the dialog is dismissed for any reason.
     */
    public static void dismissOnPip(SheetBottomDialog.SlideDialog dialog) {
        Function1<PlayerType, Unit> observer = new Function1<>() {
            @Override
            public Unit invoke(PlayerType type) {
                if (!dialog.isShowing()) {
                    PlayerType.getOnChange().removeObserver(this);
                } else if (type == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE) {
                    Logger.printDebug(() -> "Dismissing dialog due to PiP mode");
                    dialog.dismiss();
                }
                return Unit.INSTANCE;
            }
        };
        PlayerType.getOnChange().addObserver(observer);
        dialog.setOnDismissListener(d -> PlayerType.getOnChange().removeObserver(observer));
    }
}
