/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.dearrow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.shared.misc.glide.GlideRequestBuilderIntoFingerprint
import app.morphe.patches.shared.misc.glide.GlideRequestBuilderLoadGenericFingerprint
import app.morphe.patches.shared.misc.glide.GlideRequestTrackerClearAndRemoveFingerprint
import app.morphe.patches.shared.misc.glide.GlideSingleRequestBeginFingerprint
import app.morphe.patches.shared.misc.glide.GlideViewTargetGetRequestFingerprint
import app.morphe.patches.shared.misc.litho.relayout.LithoTextDrawableDrawFingerprint
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import app.morphe.patches.youtube.layout.originaltitles.videoTitlesHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLErrorCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLSuccessCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageURLHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/dearrow/DeArrowPatch;"

private const val EXTENSION_IMAGE_TARGET_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/dearrow/DeArrowPatch$ImageTargetInterface;"

private const val EXTENSION_TITLE_ICON =
    "Lapp/morphe/extension/youtube/patches/dearrow/DeArrowTitleIcon;"

private const val EXTENSION_VOTE_BUTTON =
    "Lapp/morphe/extension/youtube/patches/dearrow/DeArrowVoteButton;"

val deArrowPatch = patch(
    name = "DeArrow",
    description = "Adds options to replace video thumbnails and titles using the DeArrow API, " +
            "or replace video thumbnails with image captures from the video."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
        cronetImageURLHookPatch,
        // Thumbnails that fail to load are loaded again by mounting the Litho views again.
        lithoRelayoutPatch,
        // Titles are replaced by the same hooks that restore the original titles.
        videoTitlesHookPatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
        settingsPatch,
        legacyPlayerControlsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        fun thumbnailPreference(key: String, titleKey: String) = ListPreference(
            key = key,
            titleKey = titleKey,
            entriesKey = "morphe_dearrow_thumbnail_options_entries",
            entryValuesKey = "morphe_dearrow_thumbnail_options_entry_values"
        )

        PreferenceScreen.DEARROW.addPreferences(
            PreferenceCategory(
                key = "morphe_dearrow_titles_category",
                titleKey = "morphe_dearrow_titles_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_dearrow_titles_home"),
                    SwitchPreference("morphe_dearrow_titles_subscription"),
                    SwitchPreference("morphe_dearrow_titles_library"),
                    SwitchPreference("morphe_dearrow_titles_player"),
                    SwitchPreference("morphe_dearrow_titles_search"),
                    SwitchPreference("morphe_dearrow_titles_icon", summary = true),
                    SwitchPreference("morphe_dearrow_clean_up_titles", summary = true),
                    SwitchPreference(
                        key = "morphe_dearrow_vote_button",
                        summary = true,
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowVoteButtonSwitchPreference"
                    ),
                    SwitchPreference("morphe_dearrow_casual_mode", summary = true),
                    TextPreference("morphe_dearrow_casual_mode_min_votes", inputType = InputType.NUMBER)
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_thumbnails_category",
                titleKey = "morphe_dearrow_thumbnails_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    thumbnailPreference("morphe_dearrow_thumbnail_home", "morphe_dearrow_titles_home_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_subscription", "morphe_dearrow_titles_subscription_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_library", "morphe_dearrow_titles_library_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_player", "morphe_dearrow_titles_player_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_search", "morphe_dearrow_titles_search_title"),
                    NonInteractivePreference("morphe_dearrow_thumbnail_stills_about"),
                    ListPreference("morphe_dearrow_thumbnail_stills_time")
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_general_category",
                titleKey = "morphe_general_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    TextPreference("morphe_dearrow_api_url"),
                    SwitchPreference("morphe_dearrow_connection_toast", titleKey = "morphe_toast_on_connection_error_title", summary = true)
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_submitting_category",
                titleKey = "morphe_dearrow_submitting_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_dearrow_guidelines",
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowGuidelinesPreference",
                        selectable = true
                    ),
                    TextPreference(
                        key = "morphe_dearrow_vote_time_step",
                        titleKey = "morphe_time_adjustment_step_title",
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowTimeStepPreference",
                        inputType = InputType.NUMBER
                    ),
                    // Same setting as SponsorBlock, since DeArrow uses the same user id.
                    TextPreference(
                        key = "morphe_sb_private_user_id_Do_Not_Share",
                        titleKey = "morphe_private_user_id_title",
                        summaryKey = "morphe_dearrow_private_user_id_summary",
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowPrivateUserIdPreference"
                    )
                ),
                // Username and submissions are added at runtime.
                tag = "app.morphe.extension.youtube.settings.preference.DeArrowAccountPreferenceCategory"
            ),
            screenInfoPreferenceCategory(
                key = "morphe_dearrow_about_category",
                preferences = setOf(
                    NonInteractivePreference(
                        "morphe_dearrow_about",
                        titleKey = null,
                        // Custom about preference with link to the DeArrow website.
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowAboutPreference",
                        selectable = true
                    )
                )
            )
        )

        // Other patches use DeArrow only if this patch is included.
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        initializeTopControl(EXTENSION_VOTE_BUTTON)

        addImageURLHook(EXTENSION_CLASS)
        addImageURLSuccessCallbackHook(EXTENSION_CLASS)
        addImageURLErrorCallbackHook(EXTENSION_CLASS)

        // Thumbnails are loaded with Glide, which caches them by the URL before it's replaced.
        // The original thumbnail of a video whose DeArrow icon was tapped is loaded with another image model,
        // so the replaced thumbnail cached for the URL is not shown.
        GlideRequestBuilderLoadGenericFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getImageModel(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
            """
        )

        // The views that load the thumbnails are remembered, so a thumbnail that is loaded again,
        // mounts again only the Litho views that show the thumbnail, instead of all the shown Litho views.
        val imageModelField = GlideRequestBuilderLoadGenericFingerprint.instructionMatches.first().fieldReference
        val viewTargetType = GlideViewTargetGetRequestFingerprint.classDef.type
        val viewTargetViewField = GlideViewTargetGetRequestFingerprint.instructionMatches.first().fieldReference

        GlideRequestBuilderIntoFingerprint.method.addInstructionsWithLabels(
            0,
            """
                const/4 v0, 0x0
                instance-of v1, p1, $viewTargetType
                if-eqz v1, :load_started
                move-object v0, p1
                check-cast v0, $viewTargetType
                iget-object v0, v0, $viewTargetViewField
                :load_started
                iget-object v1, p0, $imageModelField
                invoke-static { v0, p1, v1 }, $EXTENSION_CLASS->onImageLoadStarted(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        // A thumbnail that is loaded again with the same image model, such as with another URL after it failed
        // to load, clears and begins again the request of its view target, as Glide restarts failed requests.
        // Only the image is loaded again, and the Litho view that shows it is not mounted again.
        val requestType = GlideViewTargetGetRequestFingerprint.method.returnType
        val clearRequestMethod = GlideRequestTrackerClearAndRemoveFingerprint.instructionMatches.last().methodReference
        if (clearRequestMethod.definingClass != requestType ||
            requestType !in GlideSingleRequestBeginFingerprint.classDef.interfaces
        ) {
            throw PatchException("Unexpected Glide request type: $requestType")
        }

        GlideViewTargetGetRequestFingerprint.classDef.addPatchInterface(
            EXTENSION_IMAGE_TARGET_INTERFACE,
            "patch_restartRequest",
            """
                invoke-virtual { p0 }, ${GlideViewTargetGetRequestFingerprint.method}
                move-result-object v0
                if-eqz v0, :no_request
                invoke-interface { v0 }, $clearRequestMethod
                invoke-interface { v0 }, $requestType->${GlideSingleRequestBeginFingerprint.method.name}()V
                const/4 v0, 0x1
                return v0
                :no_request
                const/4 v0, 0x0
                return v0
            """
        )

        // Litho texts handle touches only inside the bounds of the text, so a touch just above or before
        // the DeArrow icon of a title opens the video. Touches around the icon are moved onto the icon.
        val layoutField = LithoTextDrawableDrawFingerprint.instructionMatches.first().fieldReference
        val iconTouchEvent = "$EXTENSION_TITLE_ICON->iconTouchEvent(" +
                "Landroid/graphics/drawable/Drawable;Landroid/view/MotionEvent;Landroid/text/Layout;" +
                ")Landroid/view/MotionEvent;"

        LithoTextDrawableShouldHandleTouchEventFingerprint.method.addInstructions(
            0,
            """
                iget-object v0, p0, $layoutField
                invoke-static { p0, p1, v0 }, $iconTouchEvent
                move-result-object p1
            """
        )

        // The parameter registers are above v15.
        LithoTextDrawableOnTouchEventFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p1
                iget-object v2, v0, $layoutField
                invoke-static { v0, v1, v2 }, $iconTouchEvent
                move-result-object v1
                move-object/from16 p1, v1
            """
        )

        copyPlayerButtonIcons("dearrowbutton", "morphe_yt_dearrow")

        addTopControl(
            "dearrowbutton",
            "@+id/morphe_dearrow_vote_button",
            "@+id/morphe_dearrow_vote_button"
        )
    }
}
