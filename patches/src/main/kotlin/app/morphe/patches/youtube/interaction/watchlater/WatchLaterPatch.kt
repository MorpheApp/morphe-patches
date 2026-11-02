/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.watchlater

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.parseProtoInstructions
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.QueueSaveEditErrorFingerprint
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.queueSaveEditSuccessFingerprint
import app.morphe.patches.youtube.interaction.feedactions.FeedComponentHostLayoutFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.addBrowseIdHook
import app.morphe.patches.youtube.misc.navigation.addSearchResultsHook
import app.morphe.patches.youtube.misc.navigation.browseIdHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.cloneParameters

private const val EXTENSION = "Lapp/morphe/extension/youtube/patches/WatchLaterPatch;"
private const val CALLBACK = "Lapp/morphe/extension/youtube/patches/WatchLaterPatch\$Callback;"

private object ItemTouchHelperLongPressFingerprint : Fingerprint(
    name = "onLongPress",
    returnType = "V",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall("Landroid/view/MotionEvent;->getPointerId(I)I"),
        methodCall("Landroid/view/MotionEvent;->getX(I)F", location = MatchAfterWithin(12)),
        methodCall("Landroid/view/MotionEvent;->getY(I)F", location = MatchAfterWithin(4)),
        methodCall(returnType = "Z", parameters = emptyList(), location = MatchAfterWithin(12)),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(returnType = "V", parameters = listOf("L", "I"), location = MatchAfterWithin(4)),
    ),
)

private fun itemViewFingerprint(holder: String) = Fingerprint(
    definingClass = holder,
    name = "<init>",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(fieldAccess(definingClass = holder, type = "Landroid/view/View;", opcode = Opcode.IPUT_OBJECT)),
)

@Suppress("unused")
val watchLaterPatch = bytecodePatch(
    name = "Watch later improvements",
    description = "Adds an option to hide removal snackbars, widens playlist drag handles, and restores long-press reordering in Watch later.",
) {
    dependsOn(sharedExtensionPatch, settingsPatch, browseIdHookPatch, playerTypeHookPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_watch_later_hide_removal_snackbar", summary = true),
        )
        addBrowseIdHook("$EXTENSION->setBrowseId(Ljava/lang/String;)V")
        addSearchResultsHook("$EXTENSION->clearBrowseId()V")

        // Discover the obfuscated ItemTouchHelper callback method from its framework listener.
        val enabled = ItemTouchHelperLongPressFingerprint.instructionMatches[3].methodReference
        val coordinator = Fingerprint(returnType = "L", parameters = listOf("L"),
            filters = listOf(string("DragReorderCoordinator.PRESENT_CONTEXT_KEY")))
            .originalMethod.definingClass
        val movement = Fingerprint(
            returnType = "I", filters = listOf(methodCall(definingClass = coordinator), literal(196611L)),
        ).originalMethod
        val playlistCallback = movement.definingClass
        Fingerprint(definingClass = playlistCallback, name = enabled.name,
            returnType = "Z", parameters = emptyList()).method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION->isWatchLaterActive()Z
                move-result v0
                return v0
            """,
        )

        val elementsCallback = Fingerprint(superclass = enabled.definingClass, name = "<init>",
            parameters = listOf("I")).originalMethod.definingClass
        Fingerprint(superclass = elementsCallback, name = enabled.name, parameters = emptyList(),
            returnType = "Z").matchAll(1..3).forEach { match ->
            match.method.addInstructionsWithLabels(0,
                """
                    invoke-static {}, $EXTENSION->isWatchLaterActive()Z
                    move-result v0
                    if-eqz v0, :original
                    return v0
                    :original
                    nop
                """)
        }
        // Elements collections also contain headers. Only rows with native drop callbacks may move.
        val holder = movement.parameterTypes.last().toString()
        val itemView = itemViewFingerprint(holder).instructionMatches.single().fieldReference
        Fingerprint(definingClass = elementsCallback, name = movement.name).method.apply {
            val register = findFreeRegister(0)
            addInstructionsWithLabels(0,
                """
                    iget-object v$register, p2, $itemView
                    invoke-static {v$register}, $EXTENSION->allowElementDrag(Landroid/view/View;)Z
                    move-result v$register
                    if-nez v$register, :original
                    return v$register
                    :original
                    nop
                """)
        }
        val hostType = "Lcom/facebook/litho/ComponentHost;"
        val layout = FeedComponentHostLayoutFingerprint
        layout.method.cloneParameters()
        layout.clearMatch()
        layout.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach {
                addInstruction(it, "invoke-static {p0}, $EXTENSION->onHostLayout(Lcom/facebook/litho/ComponentHost;)V")
            }
        }
        Fingerprint(definingClass = hostType, name = "onInterceptTouchEvent").method.apply {
            val register = findFreeRegister(0)
            addInstructionsWithLabels(0,
                """
                    invoke-static {p0, p1}, $EXTENSION->interceptHandleTouch(Lcom/facebook/litho/ComponentHost;Landroid/view/MotionEvent;)Z
                    move-result v$register
                    if-eqz v$register, :original
                    return v$register
                    :original
                    nop
                """)
        }

        // Every native playlist row installs the same drag-handle listener.
        val touchFingerprint = Fingerprint(
            name = "<init>", parameters = listOf("Landroid/view/View;", "L", "L", "Landroid/os/Handler;", "I"),
            filters = listOf(methodCall("Landroid/view/ViewConfiguration;->getScaledTouchSlop()I")),
        )
        touchFingerprint.method.cloneParameters()
        touchFingerprint.clearMatch()
        val touch = touchFingerprint.method
        touch.addInstruction(touch.implementation!!.instructions.count() - 1,
            "invoke-static {p1}, $EXTENSION->widenDragHandle(Landroid/view/View;)V")

        // Tag each edit callback with its own playlist/actions, not a timed global suppression flag.
        val callbackType = QueueSaveEditErrorFingerprint.originalMethod.definingClass
        Fingerprint(definingClass = callbackType, name = "<init>").method.apply {
            addInstruction(implementation!!.instructions.count() - 1,
                "invoke-static {p0, p2, p3}, $EXTENSION->onEditCreated(Ljava/lang/Object;Ljava/lang/String;Ljava/util/List;)V")
        }
        val success = queueSaveEditSuccessFingerprint(callbackType).method
        val responseType = success.parameterTypes.single().toString()
        val callback = mutableClassDefBy(callbackType)
        callback.interfaces.add(CALLBACK)
        callback.addBridge("patch_parseWatchLaterResult", listOf("[B"), "Lcom/google/protobuf/MessageLite;", 4,
            """
                ${parseProtoInstructions(responseType, bytes = "p1", result = "v0", registry = "v1")}
                return-object v0
            """)
        success.addInstructions(0,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION->onEditResult(${CALLBACK}Lcom/google/protobuf/MessageLite;)Lcom/google/protobuf/MessageLite;
                move-result-object p1
                check-cast p1, $responseType
            """)
    }
}
