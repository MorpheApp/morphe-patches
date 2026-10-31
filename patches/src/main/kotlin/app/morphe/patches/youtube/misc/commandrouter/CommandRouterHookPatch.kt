/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4148
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.commandrouter

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.parseProtoInstructions
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/CommandRouterPatch;"
private const val EXTENSION_COMMAND_ROUTER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/CommandRouterPatch$CommandRouter;"

/**
 * Hooks the command router of each activity, so the extension can run commands
 * the same as the commands of server responses.
 */
internal val commandRouterHookPatch = bytecodePatch(
    description = "Hooks the command router of each activity so the extension can run commands."
) {
    dependsOn(sharedExtensionPatch)

    execute {
        CommandRouterConstructorFingerprint.let {
            val routerClass = it.classDef
            val executeCommandMethod = CommandRouterExecuteCommandFingerprint.method
            val commandClass = executeCommandMethod.parameterTypes.first().toString()

            routerClass.interfaces.add(EXTENSION_COMMAND_ROUTER_INTERFACE)
            routerClass.addBridge(
                "patch_executeCommand", listOf("[B", "Ljava/util/Map;"), "V", 5,
                """
                    ${parseProtoInstructions(commandClass, bytes = "p1", result = "v0", registry = "v1")}
                    check-cast v0, $commandClass
                    invoke-virtual { p0, v0, p2 }, $executeCommandMethod
                    return-void
                """
            )

            // Right after the super constructor, before the activity register can be reused.
            it.method.addInstruction(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0, p1 }, $EXTENSION_CLASS->" +
                        "setCommandRouter(${EXTENSION_COMMAND_ROUTER_INTERFACE}Landroid/app/Activity;)V"
            )
        }
    }
}
