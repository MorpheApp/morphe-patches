/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 * https://github.com/MorpheApp/morphe-patches/pull/4722
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val TABBED_VIEW_CLASS = "Lcom/google/android/apps/youtube/music/ui/tabs/TabbedView;"

/**
 * Field number of the tab renderer in a tab of the watch next response.
 */
internal const val TAB_RENDERER_FIELD = 58174010L

/**
 * Matched only to reach the engagement panel controller class, which owns the field
 * holding the panel currently in the container.
 */
internal object EngagementPanelControllerFingerprint : Fingerprint(
    strings = listOf(
        "EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called.",
        "[EngagementPanel] Cannot show EngagementPanel before EngagementPanelController.init() has been called."
    )
)

/**
 * Compares the identifier of the panel being shown with the one of the comments panel. The
 * static call right before the string is the helper that reads the identifier out of the
 * panel renderer, which is what tells the lyrics panel from the other panels.
 */
internal object PanelIdentifierFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Ljava/lang/String;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("music-comment-panel", location = MatchAfterWithin(2))
    )
)

/**
 * Extension stub that the patch fills in with a call to the app's panel identifier helper.
 */
internal object GetPanelIdentifierFingerprint : Fingerprint(
    definingClass = PANEL_INSTALLER_CLASS,
    name = "getPanelIdentifier",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/Object;")
)

/**
 * Tells the listeners of a tabbed view which tab was selected. The other single int parameter
 * methods of the class do not call the listeners.
 */
internal object TabbedViewTabSelectedFingerprint : Fingerprint(
    definingClass = TABBED_VIEW_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("I", "Z"),
            returnType = "V"
        )
    )
)

/**
 * Builds the watch next response of device files, which has only a Songs tab. The built tab
 * renderer is cast to its type and put in the tab, and then the tab is marked as holding a
 * tab renderer.
 */
internal object SideloadedWatchNextResponseFingerprint : Fingerprint(
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    strings = listOf("Playlist panel has no current sideloaded track."),
    filters = listOf(
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/Object;",
            location = MatchAfterWithin(3)
        ),
        literal(TAB_RENDERER_FIELD, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT, type = "I", location = MatchAfterImmediately())
    )
)

/**
 * Adds a tab and its page to a tabbed view. The page is the second view.
 */
internal object TabControllerAddTabFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Landroid/view/View;", "Landroid/view/View;", "L", "I"),
    filters = listOf(
        methodCall(definingClass = "Ljava/util/Map;", name = "put"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = TABBED_VIEW_CLASS),
        methodCall(
            definingClass = TABBED_VIEW_CLASS,
            parameters = listOf("Ljava/lang/Runnable;"),
            returnType = "V"
        )
    )
)

/**
 * Removes a tab from a tabbed view.
 */
internal object TabControllerRemoveTabFingerprint : Fingerprint(
    classFingerprint = TabControllerAddTabFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = TABBED_VIEW_CLASS,
            parameters = listOf("Ljava/lang/Runnable;"),
            returnType = "V"
        ),
        methodCall(definingClass = "Ljava/util/Map;", name = "remove")
    )
)

/**
 * Constructor of the tab model that the tab controller adds, which keeps the tab renderer.
 */
internal fun tabModelConstructorFingerprint(tabModelType: String, tabRendererType: String) = Fingerprint(
    definingClass = tabModelType,
    name = "<init>",
    parameters = listOf(tabRendererType),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = tabModelType)
    )
)
