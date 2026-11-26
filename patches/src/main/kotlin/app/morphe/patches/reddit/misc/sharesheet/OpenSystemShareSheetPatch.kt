/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5004
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/OpenSystemShareSheetPatch;"
private const val EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/OpenSystemShareSheetPatch$ShareSheetEventHandlerInterface;"

private val FieldReference.smali
    get() = "$definingClass->$name:$type"

@Suppress("unused")
val openSystemShareSheetPatch = patch(
    name = "Open system share sheet",
    description = "Adds an option to always open the system share sheet instead of the in-app share sheet. " +
        "This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Open system share sheet' requires Reddit 2026.24.0+"
            )
        }

        // Some app versions have more than one action name mapping.
        fun Fingerprint.getActionField() = matchAll().map {
            it.instructionMatches.first().fieldReference.smali
        }.distinct().single()

        // "Share via..." of the share sheet.
        val shareViaField = ShareViaActionNameFingerprint.getActionField()

        // Data of share sheets that do not share anything. Each share sheet handler checks for the same object.
        val nonShareableTextMatches = NonShareableTextFingerprint.matchAll()
        val nonShareableField = nonShareableTextMatches.map {
            it.instructionMatches.first().fieldReference.smali
        }.distinct().single()
        val shareableDataClass = nonShareableTextMatches.first().method.parameterTypes.first().toString()

        val actionClickedClasses = ShareSheetActionClickedToStringFingerprint.matchAll().map { it.classDef }

        /**
         * Adds the extension interface to the event handler of the share sheet, and replaces the event that loads
         * the content of the share sheet with the click event of "Share via..." if the system share sheet is opened.
         *
         * "Share via..." builds the link as usual, closes the share sheet and opens the system share sheet.
         */
        fun hookShareSheet(
            screenInitFingerprint: Fingerprint,
            argsToStringFingerprint: Fingerprint,
            hasActions: Boolean
        ) {
            val eventHandlerCall = screenInitFingerprint.instructionMatches.last()
            val eventHandlerMethod = eventHandlerCall.methodReference
            val eventClass = eventHandlerMethod.parameterTypes.first().toString()
            val eventHandlerClass = mutableClassDefBy(eventHandlerMethod.definingClass)

            val argsClass = argsToStringFingerprint.classDef
            val argsField = eventHandlerClass.fields.single { it.type == argsClass.type }
            fun argsFieldOfType(type: String) = argsClass.fields.single { it.type == type }
            fun getArgsFieldSmali(field: FieldReference) =
                """
                    iget-object v0, p0, ${argsField.smali}
                    iget-object v0, v0, ${field.smali}
                    return-object v0
                """

            eventHandlerClass.apply {
                addPatchInterface(
                    EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE,
                    "patch_isShareable",
                    """
                        iget-object v0, p0, ${argsField.smali}
                        iget-object v0, v0, ${argsFieldOfType(shareableDataClass).smali}
                        sget-object v1, $nonShareableField
                        if-eq v0, v1, :non_shareable
                        const/4 v0, 0x1
                        return v0
                        :non_shareable
                        const/4 v0, 0x0
                        return v0
                    """
                )

                addPatchInterface(
                    EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE,
                    "patch_getActions",
                    if (hasActions) {
                        getArgsFieldSmali(argsFieldOfType("Ljava/util/List;"))
                    } else {
                        """
                            const/4 v0, 0x0
                            return-object v0
                        """
                    }
                )

                addPatchInterface(
                    EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE,
                    "patch_getShareTrigger",
                    getArgsFieldSmali(argsFieldOfType(SHARE_TRIGGER_CLASS))
                )

                // Each share sheet has its own click event class, with the action as the first parameter.
                // Other parameters, such as the position of the action, are null.
                val actionClickedClass = actionClickedClasses.single { eventClass in it.interfaces }
                val constructor = actionClickedClass.methods.single { it.name == "<init>" }
                val otherParameters = constructor.parameterTypes.drop(1)
                if (otherParameters.any { !it.startsWith("L") }) {
                    throw PatchException("Unexpected click event parameters: $constructor")
                }
                val nullRegisters = otherParameters.indices.map { "v${it + 2}" }

                addPatchInterface(
                    EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE,
                    "patch_getShareViaEvent",
                    """
                        new-instance v0, ${actionClickedClass.type}
                        sget-object v1, $shareViaField
                        ${nullRegisters.joinToString("\n") { "const/4 $it, 0x0" }}
                        invoke-direct { ${(listOf("v0", "v1") + nullRegisters).joinToString()} }, $constructor
                        return-object v0
                    """
                )
            }

            screenInitFingerprint.method.apply {
                val registers = eventHandlerCall.instruction.registersUsed
                val eventHandlerRegister = registers[0]
                val eventRegister = registers[1]

                addInstructions(
                    eventHandlerCall.index,
                    """
                        invoke-static { v$eventRegister, v$eventHandlerRegister }, $EXTENSION_CLASS->getScreenInitEvent(Ljava/lang/Object;$EXTENSION_SHARE_SHEET_EVENT_HANDLER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$eventRegister
                        check-cast v$eventRegister, $eventClass
                    """
                )
            }
        }

        hookShareSheet(
            ActionSheetScreenInitFingerprint,
            ActionSheetArgsToStringFingerprint,
            hasActions = true
        )

        // "Share" of overflow menus shows the share targets in the overflow menu,
        // without sending the event that loads the content of a share sheet.
        HandleShareSheetActionFingerprint.method.apply {
            val actionRegister = p0Register + 1
            val registerProvider = getFreeRegisterProvider(0, 2, actionRegister)
            val openShareSheetRegister = registerProvider.getFreeRegister()
            val shareViaRegister = registerProvider.getFreeRegister()

            addInstructions(
                0,
                """
                    sget-object v$openShareSheetRegister, ${OpenShareSheetActionNameFingerprint.getActionField()}
                    sget-object v$shareViaRegister, $shareViaField
                    invoke-static { v$actionRegister, v$openShareSheetRegister, v$shareViaRegister }, $EXTENSION_CLASS->getShareSheetAction(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$actionRegister
                    check-cast v$actionRegister, ${parameterTypes.first()}
                """
            )
        }

        // Before 2026.44.0 the share sheet of the "shareSheetOverhaulM2" experiment is the action sheet.
        if (!is_2026_44_0_or_greater) {
            hookShareSheet(
                ShareBottomSheetScreenInitFingerprint,
                ShareBottomSheetArgsToStringFingerprint,
                hasActions = false
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
