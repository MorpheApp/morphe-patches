/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Leaves the native name of the current account's Downloads database in v0. */
internal fun offlineDownloadDatabaseNameInstructions(
    refreshMethod: MethodReference,
    playerResponseModelType: String,
    descriptorType: String,
): String {
    val resolverFingerprint = offlinePositionAccountFingerprint(playerResponseModelType, descriptorType)
    val resolver = resolverFingerprint.originalMethod
    val (accountSourceMatch, accountGetterMatch) = resolverFingerprint.instructionMatches.takeLast(7)
    val accountSource = accountSourceMatch.fieldReference
    val accountGetter = accountGetterMatch.methodReference
    val (accountIdMatch, _, databaseNameMatch) =
        offlineAccountDatabaseNameFingerprint(accountGetter.returnType).instructionMatches

    val servicesInstructions = if (is_20_51_or_greater) {
        val matches = offlinePositionServicesFingerprint(resolver, accountSource.definingClass).instructionMatches
        val supplier = matches[0].fieldReference
        val getServices = matches[1].methodReference
        """
            iget-object v0, p0, $supplier
            invoke-interface { v0 }, $getServices
            move-result-object v0
            check-cast v0, ${accountSource.definingClass}
        """.trimIndent()
    } else {
        "move-object v0, p0"
    }

    // Use the account that YouTube's position lookup would select for this player.
    val bridgeName = "patch_getOfflineDownloadDatabaseName"
    resolverFingerprint.classDef.addBridge(
        bridgeName,
        emptyList(),
        "Ljava/lang/String;",
        2,
        """
            $servicesInstructions
            iget-object v0, v0, $accountSource
            check-cast v0, ${accountGetter.definingClass}
            invoke-interface { v0 }, $accountGetter
            move-result-object v0
            if-eqz v0, :no_account
            invoke-interface { v0 }, ${accountIdMatch.methodReference}
            move-result-object v0
            invoke-static { v0 }, ${databaseNameMatch.methodReference}
            move-result-object v0
            return-object v0
            :no_account
            const/4 v0, 0x0
            return-object v0
        """
    )

    val resolverField = offlinePositionResolverFieldFingerprint(refreshMethod, resolver)
        .instructionMatches.first().fieldReference
    return """
        move-object/from16 v0, p0
        iget-object v0, v0, $resolverField
        invoke-virtual { v0 }, ${resolver.definingClass}->$bridgeName()Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :skip
    """.trimIndent()
}

private fun offlinePositionAccountFingerprint(playerResponseModelType: String, descriptorType: String) = Fingerprint(
    parameters = when {
        is_21_23_or_greater -> listOf(descriptorType, playerResponseModelType, "L")
        is_20_51_or_greater -> listOf("L", "L")
        else -> listOf("L")
    },
    returnType = if (is_21_23_or_greater) "Ljava/lang/Long;" else "V",
    filters = buildList {
        if (!is_21_23_or_greater) {
            add(fieldAccess(opcode = Opcode.IGET_OBJECT, type = playerResponseModelType))
            add(fieldAccess(opcode = Opcode.IGET_OBJECT, type = descriptorType))
        }
        add(fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/Object;"))
        add(methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = emptyList(),
            returnType = "L",
            location = MatchAfterImmediately()
        ))
        add(opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()))
        add(methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterImmediately()
        ))
        add(opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()))
        add(fieldAccess(opcode = Opcode.SGET_OBJECT, location = MatchAfterImmediately()))
        add(methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "I",
            location = MatchAfterImmediately()
        ))
    }
)

private fun offlinePositionServicesFingerprint(resolver: MethodReference, servicesType: String) = Fingerprint(
    definingClass = resolver.definingClass,
    name = resolver.name,
    parameters = resolver.parameterTypes.map(CharSequence::toString),
    returnType = resolver.returnType,
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(servicesType, location = MatchAfterWithin(2))
    )
)

/** The account cleanup callback computes the same database name used when opening it. */
private fun offlineAccountDatabaseNameFingerprint(accountType: String) = Fingerprint(
    name = "run",
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = accountType,
            parameters = emptyList(),
            returnType = "Ljava/lang/String;",
            opcode = Opcode.INVOKE_INTERFACE
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Ljava/lang/String;",
            opcode = Opcode.INVOKE_STATIC,
            location = MatchAfterImmediately()
        ),
        methodCall(
            "Landroid/content/Context;->deleteDatabase(Ljava/lang/String;)Z",
            location = MatchAfterWithin(4)
        )
    )
)

private fun offlinePositionResolverFieldFingerprint(refresh: MethodReference, resolver: MethodReference) = Fingerprint(
    definingClass = refresh.definingClass,
    name = refresh.name,
    parameters = refresh.parameterTypes.map(CharSequence::toString),
    returnType = refresh.returnType,
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", type = resolver.definingClass),
        methodCall(resolver, location = MatchAfterWithin(6))
    )
)
