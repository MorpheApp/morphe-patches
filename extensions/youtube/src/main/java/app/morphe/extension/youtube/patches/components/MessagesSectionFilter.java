/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

/**
 * Hides the "Messages" section shown at the top of the Notifications tab.
 * <p>
 * The section is made of two sibling top level components:
 * <ul>
 *     <li>a {@code shelf_header.e} cell holding the section title,</li>
 *     <li>a {@code linear_layout.e} cell wrapping the
 *         {@code connections_inbox_zero_state} "Invite others to message" card.</li>
 * </ul>
 * <p>
 * The card is identified by a unique buffer string and is language independent.
 * <p>
 * Every section header of the Notifications tab ("Messages", "Notifications", "Today",
 * "This week", "Older") uses the exact same identifier and an otherwise byte identical
 * buffer, so the only thing that distinguishes the messages header is its title, which is
 * localized by the server and not backed by an app string resource. Matching the title is
 * therefore unavoidable, and everything is scoped to the Notifications tab to keep any
 * mismatch contained to that tab.
 */
@SuppressWarnings("unused")
public final class MessagesSectionFilter extends Filter {

    /**
     * Unique to the "Invite others to message" card.
     */
    private final ByteArrayFilterGroup inviteCardBuffer = new ByteArrayFilterGroup(
            null,
            "connections_inbox_zero_state"
    );

    /**
     * Title of the messages section header, as sent by the server.
     */
    private final ByteArrayFilterGroup sectionHeaderTitleBuffer = new ByteArrayFilterGroup(
            null,
            "Messages"
    );

    private final StringFilterGroup inviteCard;
    private final StringFilterGroup sectionHeader;

    public MessagesSectionFilter() {
        inviteCard = new StringFilterGroup(
                Settings.HIDE_MESSAGES_SECTION,
                "linear_layout.e"
        );

        sectionHeader = new StringFilterGroup(
                Settings.HIDE_MESSAGES_SECTION,
                "shelf_header.e"
        );

        addIdentifierCallbacks(inviteCard, sectionHeader);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        // Both identifiers are generic and used all over the app,
        // so only filter the Notifications tab.
        if (contentIndex != 0
                || NavigationButton.getSelectedNavigationButton() != NavigationButton.NOTIFICATIONS) {
            return false;
        }

        if (matchedGroup == inviteCard) {
            return inviteCardBuffer.check(buffer).isFiltered();
        }

        return sectionHeaderTitleBuffer.check(buffer).isFiltered();
    }
}
