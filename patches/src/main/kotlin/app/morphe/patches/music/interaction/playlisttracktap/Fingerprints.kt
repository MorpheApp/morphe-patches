/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3446
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.playlisttracktap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Resolves a command with every registered command resolver, in order.
 * Every command a tap sends is routed through here before it is resolved.
 *
 * The other command resolver does not run the resolvers on an executor.
 */
internal object CommandResolverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/concurrent/Executor;",
            name = "execute"
        ),
        // The message is formatted differently by some app versions.
        string("Unknown command not resolved", StringComparisonType.CONTAINS)
    )
)

/**
 * Builds a command holding a watch endpoint.
 * Parameters are the video id, playlist id, playlist index, start time,
 * player params, params and whether the watch endpoint is a music video.
 */
internal object WatchEndpointCommandBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "I",
        "F",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    ),
    filters = listOf(
        // Default instance of the watch endpoint.
        fieldAccess(opcode = Opcode.SGET_OBJECT),
        // Creates the watch endpoint builder.
        methodCall(name = "createBuilder", opcode = Opcode.INVOKE_VIRTUAL),

        // Video id and playlist id are the first two strings set, in the order of the parameters.
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/String;"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/String;"),

        // The is music video boolean is set before the presence bits and then the playlist index.
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN),
        fieldAccess(opcode = Opcode.IPUT, type = "I"),
        fieldAccess(opcode = Opcode.IPUT, type = "I"),

        // Default instance of the command holding the watch endpoint.
        fieldAccess(opcode = Opcode.SGET_OBJECT),
        // Extension field of the watch endpoint inside the command.
        fieldAccess(opcode = Opcode.SGET_OBJECT),
        // Sets the watch endpoint extension of the command builder.
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "V", parameters = listOf("L", "Ljava/lang/Object;"))
    )
)

/**
 * Resolves the queue add endpoint, which the long press 'Add to queue' menu item sends.
 */
internal object QueueAddEndpointCommandFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        // Extension field of the queue add endpoint inside the command.
        fieldAccess(opcode = Opcode.SGET_OBJECT)
    ),
    strings = listOf("Move performed instead of add while casting.")
)

/**
 * Gets an extension of the command builder, such as the watch endpoint.
 *
 * @param extendableBuilderClass Class of the command builder that sets the extension.
 * @param extensionType Type of the extension descriptor.
 */
internal fun getCommandBuilderExtensionFingerprint(
    extendableBuilderClass: String,
    extensionType: String
) = Fingerprint(
    definingClass = extendableBuilderClass,
    returnType = "Ljava/lang/Object;",
    parameters = listOf(extensionType)
)
