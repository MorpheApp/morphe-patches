/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SpecificNetworkErrorViewControllerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "ic_offline_no_content_upside_down"),
        resourceLiteral(ResourceType.STRING, "offline_no_content_body_text_not_offline_eligible"),
        methodCall(name = "getString", returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object GmsDeviceComplianceCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"), // Rx single emitter.
    filters = listOf(
        string("failsafe_enable_gms_device_compliance_check"),
        // GServices lookup of the flag above.
        methodCall(parameters = listOf("Ljava/lang/String;"), returnType = "Z"),
        // Emits false and returns when the check is turned off.
        methodCall(
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "V",
            opcode = Opcode.INVOKE_VIRTUAL
        )
    )
)
