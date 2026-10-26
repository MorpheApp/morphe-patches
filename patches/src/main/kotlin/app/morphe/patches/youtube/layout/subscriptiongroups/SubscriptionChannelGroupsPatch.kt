/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.subscriptiongroups

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/subscriptiongroups/SubscriptionGroupsPatch;"

private const val PREFERENCE_CLASS =
    "app.morphe.extension.youtube.settings.preference.SubscriptionGroupsPreference"

/**
 * Runtime hook choice: [hookNavigationButtonCreated] from
 * navigationBarHookPatch (misc/navigation) is the closest existing
 * hook that reports a top-level screen surface. It is a registration
 * helper backed by the pivot bar fingerprints in
 * misc/navigation/Fingerprints.kt (PivotBarButtonsCreate*View), and it
 * is already used the same way by NavigationBarPatch and
 * SeriesTrackerPatch: the extension gets
 * navigationTabCreated(NavigationButton, View) for every pivot bar
 * tab, and this patch's extension acts only on SUBSCRIPTIONS.
 *
 * No new fingerprint is invented here. The fragile part is in the
 * extension, not the hook: from the tab view it climbs to the root
 * view and heuristically finds the feed list and a place to insert
 * the chip bar, logging "Subscription groups bar candidate: ..."
 * lines. If a YouTube version restructures the pivot bar or the
 * subscriptions feed tree, this attachment point is the first place
 * to re-verify (see SUBSCRIPTION_GROUPS_README.md), exactly like the
 * player overlay fingerprint of the danmaku patch.
 */
@Suppress("unused")
val subscriptionChannelGroupsPatch = bytecodePatch(
    name = "Subscription channel groups",
    description = "Adds an option to organize subscribed channels into user-created " +
        "groups and filter the subscriptions feed with group chips at the top " +
        "of the subscriptions screen.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_subscription_groups", summary = true),
            NonInteractivePreference(
                key = "morphe_subscription_groups_manage",
                tag = PREFERENCE_CLASS,
                selectable = true
            ),
        )

        // Notifies the extension when a pivot bar tab view is created.
        // The extension filters for the subscriptions tab and attaches
        // the group chip bar to the subscriptions feed.
        hookNavigationButtonCreated(EXTENSION_CLASS)
    }
}
