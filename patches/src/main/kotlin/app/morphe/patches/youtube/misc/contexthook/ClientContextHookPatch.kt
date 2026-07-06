/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneParameters
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private lateinit var clientFormFactorFieldString: String
private lateinit var clientInfoDefiningClass: String
private lateinit var clientInfoFieldString: String
private lateinit var clientVersionFieldString: String
private lateinit var messageLiteBuilderFieldString: String
private lateinit var messageLiteBuilderMethodString: String
private lateinit var osNameFieldString: String

enum class Endpoint(
    vararg val parentFingerprints: Fingerprint,
    var smaliInstructions: String = "",
) {
    BROWSE(BrowseEndpointParentFingerprint),
    GET_WATCH(
        GetWatchEndpointConstructorPrimaryFingerprint,
        GetWatchEndpointConstructorSecondaryFingerprint,
    ),
    GUIDE(GuideEndpointConstructorFingerprint),
    NEXT(NextEndpointParentFingerprint),
    PLAYER(PlayerEndpointParentFingerprint),
    REEL(
        // 21.21+ removed "reel/create_reel_items" and the replacement isn't clear.
        *(arrayOf(
            ReelItemWatchEndpointConstructorFingerprint,
            ReelWatchSequenceEndpointConstructorFingerprint,
        ) + if (!is_21_21_or_greater) arrayOf(ReelCreateItemsEndpointConstructorFingerprint) else emptyArray())
    ),
    SEARCH(SearchRequestBuildParametersFingerprint),
    TRANSCRIPT(TranscriptEndpointConstructorFingerprint);
}

val clientContextHookPatch = bytecodePatch(
    description = "Hooks the context body of the endpoint.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        BuildDummyClientContextBodyFingerprint.let {
            it.method.apply {
                val clientInfoIndex = it.instructionMatches.last().index
                val clientVersionIndex = it.instructionMatches[2].index
                val messageLiteBuilderIndex = it.instructionMatches.first().index

                val clientInfoField = getInstruction<ReferenceInstruction>(clientInfoIndex).reference as FieldReference
                clientInfoDefiningClass = clientInfoField.definingClass
                clientInfoFieldString = "${clientInfoField.definingClass}->${clientInfoField.name}:${clientInfoField.type}"

                val clientVersionField = getInstruction<ReferenceInstruction>(clientVersionIndex).reference as FieldReference
                clientVersionFieldString = "${clientVersionField.definingClass}->${clientVersionField.name}:${clientVersionField.type}"

                val messageLiteBuilderField = getInstruction<ReferenceInstruction>(messageLiteBuilderIndex).reference as FieldReference
                messageLiteBuilderFieldString = "${messageLiteBuilderField.definingClass}->${messageLiteBuilderField.name}:${messageLiteBuilderField.type}"
            }
        }

        AuthenticationChangeListenerFingerprint.let {
            val messageLiteBuilderIndex = it.instructionMatches[1].index
            val methodRef = it.method.getInstruction<ReferenceInstruction>(messageLiteBuilderIndex).reference as MethodReference

            val params = methodRef.parameterTypes.joinToString("")
            messageLiteBuilderMethodString = "${methodRef.definingClass}->${methodRef.name}($params)${methodRef.returnType}"
        }

        BuildClientContextBodyFingerprint.let {
            it.method.apply {
                val osNameIndex = it.instructionMatches[1].index
                val osNameField = getInstruction<ReferenceInstruction>(osNameIndex).reference as FieldReference
                osNameFieldString = "${osNameField.definingClass}->${osNameField.name}:${osNameField.type}"
            }
        }

        val clientFormFactorOrdinalReference = ClientFormFactorEnumOrdinalFingerprint.method as MethodReference

        val clientFormFactorField = setClientFormFactorFingerprint(clientFormFactorOrdinalReference)
            .instructionMatches.first()
            .instruction.let { it as ReferenceInstruction }.reference as FieldReference

        clientFormFactorFieldString = "${clientFormFactorField.definingClass}->${clientFormFactorField.name}:${clientFormFactorField.type}"
    }

    finalize {
        val helperMethodName = "patch_setClientContext"

        Endpoint.entries.filter {
            it.smaliInstructions.isNotEmpty()
        }.forEach { endpoint ->
            endpoint.parentFingerprints.forEach { parentFingerprint ->

                endpointRequestBodyFingerprint(parentFingerprint).let {
                    // 21.05+ clobbers p0 register.
                    it.method.cloneParameters().apply {
                        it.classDef.methods.add(
                            ImmutableMethod(
                                definingClass,
                                helperMethodName,
                                emptyList(),
                                "V",
                                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                                annotations,
                                null,
                                MutableMethodImplementation(5),
                            ).toMutable().apply {
                                addInstructionsWithLabels(
                                    0,
                                    """
                                        invoke-virtual { p0 }, $messageLiteBuilderMethodString
                                        move-result-object v0
                                        iget-object v0, v0, $messageLiteBuilderFieldString
                                        check-cast v0, $clientInfoDefiningClass
                                        iget-object v1, v0, $clientInfoFieldString
                                        if-eqz v1, :ignore
                                    """ + endpoint.smaliInstructions +
                                            """
                                                :ignore
                                                return-void
                                            """
                                )
                            }
                        )

                        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                            addInstructionsAtControlFlowLabel(
                                index,
                                "invoke-direct/range { p0 .. p0 }, $definingClass->$helperMethodName()V"
                            )
                        }
                    }
                }
            }
        }
    }
}

fun addClientFormFactorHook(endPoint: Endpoint, descriptor: String) {
    val smaliInstructions = """
        iget v2, v1, $clientFormFactorFieldString
        invoke-static { v2 }, $descriptor
        move-result v2
        iput v2, v1, $clientFormFactorFieldString
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addClientVersionHook(endPoint: Endpoint, descriptor: String) {
    val smaliInstructions = """
        iget-object v2, v1, $clientVersionFieldString
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $clientVersionFieldString
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addOSNameHook(endPoint: Endpoint, descriptor: String) {
    val smaliInstructions = """
        iget-object v2, v1, $osNameFieldString
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $osNameFieldString
        """

    endPoint.smaliInstructions += smaliInstructions
}
