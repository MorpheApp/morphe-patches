package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class InfoCardsFilter extends Filter {

    public InfoCardsFilter() {
        addIdentifierCallbacks(
                new StringFilterGroup(
                        Settings.HIDE_INFO_CARDS,
                        "info_card_teaser_overlay.e"
                )
        );
    }
}
