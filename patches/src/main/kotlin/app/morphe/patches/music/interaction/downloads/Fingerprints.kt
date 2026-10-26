/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CommandResolverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L", "L", "Ljava/util/Map;"),
    filters = listOf(
        anyInstruction(
            string("CommandResolver threw exception during resolution")
        )
    )
)

/**
 * Request of the 'ypc/get_offline_upsell' endpoint.
 */
internal object OfflineUpsellRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string("ypc/get_offline_upsell")
    )
)

/**
 * Handles the Download command of accounts that are offered Premium instead,
 * by showing a progress dialog and then the Premium upsell of the endpoint.
 */
internal fun offlineUpsellCommandFingerprint(offlineUpsellRequestType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        string("com.google.android.libraries.youtube.logging.interaction_logger"),
        newInstance(offlineUpsellRequestType)
    )
)

internal object OfflineVideoEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf("Object is not an offlineable video: %s"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "L"
        ),
        methodCall(
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL),
            parameters = listOf("Ljava/lang/String;", "L", "L", "L"),
            returnType = "V"
        )
    )
)
