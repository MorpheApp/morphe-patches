/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5254
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object TabLayoutTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        anyInstruction(
            string("FEmusic_search"), // 8.49 and lower.
            string("FEsearch"), // 8.50+
            newInstance("Ljava/util/ArrayList;") // 9.21+
        ),

        // Hide navigation label.
        resourceLiteral(
            type = ResourceType.ID,
            name = "text1"
        ),
        methodCall(
            smali = "Landroid/view/View;->findViewById(I)Landroid/view/View;",
            location = MatchAfterWithin(5)
        ),
        checkCast(
            type = "Landroid/widget/TextView;",
            location = MatchAfterWithin(5)
        ),

        // Set navigation enum.
        anyInstruction(
            opcode(Opcode.SGET_OBJECT),
            opcode(Opcode.IGET_OBJECT)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            type = "I",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "L",
            parameters = listOf("I"),
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),

        // Hide navigation buttons.
        methodCall(
            name = "getVisibility"
        )
    )
)

/**
 * Matches the method that creates the navigation buttons, at the field put of the list of
 * the navigation button protos, which the buttons are then created from.
 */
internal object NavigationButtonsListFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("Ljava/util/ArrayList;"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/util/List;",
            location = MatchAfterWithin(5)
        ),
        resourceLiteral(ResourceType.LAYOUT, "image_with_text_tab")
    )
)

/**
 * Matches the method that makes the navigation buttons shown before the guide response is loaded.
 * The last filter is the call of the factory of the navigation button protos,
 * which takes the browse id, the label and the icon type.
 */
internal object DefaultNavigationButtonsFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "pivot_home"),
        string("FEmusic_home"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "L"),
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Matches the method that creates the menu of the toolbar.
 * The last filter is the id of the search button, used right after the app sets the icons of the menu items.
 */
internal object MusicActivityOnCreateOptionsMenuFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;",
    name = "onCreateOptionsMenu",
    returnType = "Z",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        resourceLiteral(ResourceType.MENU, "main_menu"),
        methodCall(
            smali = "Landroid/view/MenuInflater;->inflate(ILandroid/view/Menu;)V",
            location = MatchAfterWithin(5)
        ),
        resourceLiteral(ResourceType.ID, "action_search")
    )
)

/**
 * Matches the method that replaces the buttons of the toolbar menu with the buttons a page
 * gets from the server. Depending on a server flag, it removes all the buttons of the menu first.
 */
internal object ToolbarServerButtonsFingerprint : Fingerprint(
    returnType = "V",
    // 9.47+ has a second parameter with the list of the buttons.
    filters = listOf(
        methodCall(smali = "Ljava/util/List;->isEmpty()Z"),
        methodCall(smali = "Landroid/view/Menu;->clear()V"),
        methodCall(
            name = "forEach",
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Matches the onMeasure method of the watch-while layout, which gives the view the pages are in
 * its height, the height of the layout without the height of the navigation bar.
 * The height is the third argument of the first static call that takes a view and two sizes
 * after the log of a navigation bar without height.
 */
internal object WatchWhileLayoutOnMeasureFingerprint : Fingerprint(
    definingClass = "WatchWhileLayout;",
    name = "onMeasure",
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = listOf(
        string("visiblePivotBarHeight is 0"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf("Landroid/view/View;", "I", "I")
        )
    )
)

/**
 * Matches every method that reads the feature flag of the floating miniplayer,
 * a rounded card with room on its sides and below it. 9.39+
 */
internal object FloatingMiniplayerFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45823962L)
    )
)

internal object MediaRouteButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    strings = listOf("MediaRouteButton")
)

internal object PlayerOverlayChipFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_overlay_chip")
    )
)

internal object HistoryMenuItemFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "history_menu_item"),
        methodCall(smali = "Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;"),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
    ),
    custom = { _, classDef ->
        classDef.methods.count() == 4 || classDef.methods.count() == 5
    }
)

internal object HistoryMenuItemOfflineTabFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "offline_settings_menu_item"),
        resourceLiteral(ResourceType.ID, "history_menu_item"),
        methodCall(smali = "Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;"),
    )
)

internal object SearchActionViewFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/search/SearchActionProvider;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "search_button")
    )
)

/**
 * Matches the search toolbar view constructor in newer app targets,
 * and the search fragments that build the toolbar in older app targets.
 */
internal object SearchVoiceButtonsFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "voice_search"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3)),
        resourceLiteral(ResourceType.ID, "sound_search"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3))
    )
)

/**
 * Matches the constructor of the player page presenter, which finds the Ask Music buttons
 * of the player and of the miniplayer. 9.38+
 */
internal object PlayerAskMusicButtonFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "ask_music_global_entry_button")
    )
)

/**
 * Matches the method that shows the Ask Music buttons for a button renderer,
 * or hides the buttons and the space next to them if there is no renderer.
 */
internal object PlayerAskMusicButtonBindFingerprint : Fingerprint(
    classFingerprint = PlayerAskMusicButtonFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(smali = "Landroid/widget/Space;->setVisibility(I)V")
    )
)

/**
 * Matches the method that binds the Ask Music button renderer to the buttons of the player.
 * The first filter is the call that gets the renderer, which the fullscreen player uses too.
 */
internal object PlayerAskMusicButtonRendererFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            returnType = { PlayerAskMusicButtonBindFingerprint.originalMethod.parameterTypes.first() },
            opcode = Opcode.INVOKE_STATIC
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(
            reference = { PlayerAskMusicButtonBindFingerprint.originalMethod },
            location = MatchAfterWithin(5)
        )
    )
)

internal object TopBarMenuItemImageViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "top_bar_menu_item_image_view"),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Lj$/util/Optional;"
        )
    )
)
