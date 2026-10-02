/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3446
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.playlisttracktap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PlaylistTrackTapPatch;"

@Suppress("unused")
val playlistTrackTapPatch = bytecodePatch(
    name = "Playlist track tap action",
    description = "Adds an option to play only the tapped track of a playlist or album, " +
            "or to add it to the queue, instead of replacing the queue with the whole playlist.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_music_playlist_track_tap_action")
        )

        // region Resolve the obfuscated proto classes.

        val watchEndpointCommandBuilder = WatchEndpointCommandBuilderFingerprint.method
        val builderInstructions = watchEndpointCommandBuilder.implementation!!.instructions.toList()

        // The builder creates the watch endpoint from its default instance,
        // which is the first static field it reads of its own type.
        val watchEndpointDefaultInstanceField = builderInstructions.firstNotNullOf {
            it.fieldReference(Opcode.SGET_OBJECT)?.takeIf { field -> field.type == field.definingClass }
        }
        val watchEndpointClass = watchEndpointDefaultInstanceField.definingClass

        fun watchEndpointFieldWrites(opcode: Opcode) = builderInstructions.withIndex().filter { (_, instruction) ->
            instruction.fieldReference(opcode)?.definingClass == watchEndpointClass
        }

        // Video id and playlist id are the first two strings set, in the order of the parameters.
        val playlistIdField = watchEndpointFieldWrites(Opcode.IPUT_OBJECT)
            .map { (_, instruction) -> instruction.fieldReference(Opcode.IPUT_OBJECT)!! }
            .filter { it.type == "Ljava/lang/String;" }
            .getOrNull(1)?.name ?: throw PatchException("Could not find the playlist id field")

        val intFieldWrites = watchEndpointFieldWrites(Opcode.IPUT)
        // The presence bits are set before the first field is.
        val presenceBitsField = intFieldWrites.first().value.fieldReference(Opcode.IPUT)!!.name
        // The playlist index is the only int field set from a parameter, the third one.
        val playlistIndexParameterRegister = watchEndpointCommandBuilder.implementation!!.registerCount -
                watchEndpointCommandBuilder.parameterTypes.size + 2
        val playlistIndexField = intFieldWrites.map { it.value }.first {
            (it as TwoRegisterInstruction).registerA == playlistIndexParameterRegister
        }.fieldReference(Opcode.IPUT)!!.name

        val setExtensionIndex = builderInstructions.indexOfFirst {
            val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                    reference?.returnType == "V" &&
                    reference.parameterTypes.size == 2 &&
                    reference.parameterTypes[1].toString() == "Ljava/lang/Object;"
        }
        if (setExtensionIndex < 0) throw PatchException("Could not find the set extension method")
        val setExtensionMethod = builderInstructions[setExtensionIndex].methodReference()
        val extensionDescriptorType = setExtensionMethod.parameterTypes.first().toString()
        val extendableBuilderClass = setExtensionMethod.definingClass

        val watchEndpointExtensionField = builderInstructions.subList(0, setExtensionIndex).last {
            it.opcode == Opcode.SGET_OBJECT
        }.fieldReference(Opcode.SGET_OBJECT)!!

        // The command holding the watch endpoint is created from its default instance.
        val commandDefaultInstanceField = builderInstructions.mapNotNull {
            it.fieldReference(Opcode.SGET_OBJECT)?.takeIf { field ->
                field.type == field.definingClass && field.definingClass != watchEndpointClass
            }
        }.first()
        val commandClass = commandDefaultInstanceField.definingClass

        val getExtensionMethod = findMethodInHierarchy(extendableBuilderClass) {
            it.parameterTypes.size == 1 &&
                    it.parameterTypes.first().toString() == extensionDescriptorType &&
                    it.returnType == "Ljava/lang/Object;"
        }

        // GeneratedMessageLite and its builder, whose method names are not obfuscated.
        val messageClass = classDefBy(watchEndpointClass).superclass!!
        val messageBuilderClass = classDefBy(messageClass).methods.first {
            it.name == "toBuilder" && it.parameterTypes.isEmpty()
        }.returnType

        val queueAddEndpointExtensionField = QueueAddEndpointCommandFingerprint.method
            .implementation!!.instructions.firstNotNullOf {
                it.fieldReference(Opcode.SGET_OBJECT)?.takeIf { field ->
                    field.type == watchEndpointExtensionField.type
                }
            }
        val queueAddEndpointClass = queueAddEndpointExtensionField.definingClass
        val queueAddEndpointDefaultInstanceField = classDefBy(queueAddEndpointClass).fields.first {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == queueAddEndpointClass
        }

        // endregion

        // region Implement the extension methods using the proto classes.

        val toBuilder = "$messageClass->toBuilder()$messageBuilderClass"
        val copyOnWrite = "$messageBuilderClass->copyOnWrite()V"
        val build = "$messageBuilderClass->build()$messageClass"
        val instanceField = "$messageBuilderClass->instance:$messageClass"
        val watchEndpointExtension = watchEndpointExtensionField.toString()
        val setExtension = setExtensionMethod.toString()

        implementExtensionMethod(
            "getWatchEndpoint",
            registerCount = 3,
            """
                instance-of v0, p0, $commandClass
                if-eqz v0, :none
                check-cast p0, $messageClass
                invoke-virtual { p0 }, $toBuilder
                move-result-object v0
                check-cast v0, $extendableBuilderClass
                sget-object v1, $watchEndpointExtension
                invoke-virtual { v0, v1 }, $getExtensionMethod
                move-result-object v0
                # A command without a watch endpoint returns the default instance.
                sget-object v1, $watchEndpointDefaultInstanceField
                if-eq v0, v1, :none
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """
        )

        implementExtensionMethod(
            "createSingleTrackCommand",
            registerCount = 3,
            """
                # Copy the watch endpoint without the playlist id and index.
                check-cast p1, $messageClass
                invoke-virtual { p1 }, $toBuilder
                move-result-object v0
                invoke-virtual { v0 }, $copyOnWrite
                iget-object v1, v0, $instanceField
                check-cast v1, $watchEndpointClass
                const-string v2, ""
                iput-object v2, v1, $watchEndpointClass->$playlistIdField:Ljava/lang/String;
                const/4 v2, 0x0
                iput v2, v1, $watchEndpointClass->$playlistIndexField:I
                # Clear the presence bits of the playlist id (0x2) and index (0x4).
                iget v2, v1, $watchEndpointClass->$presenceBitsField:I
                and-int/lit8 v2, v2, -0x7
                iput v2, v1, $watchEndpointClass->$presenceBitsField:I
                invoke-virtual { v0 }, $build
                move-result-object v1

                # Copy the command with the new watch endpoint.
                check-cast p0, $messageClass
                invoke-virtual { p0 }, $toBuilder
                move-result-object v0
                check-cast v0, $extendableBuilderClass
                sget-object v2, $watchEndpointExtension
                invoke-virtual { v0, v2, v1 }, $setExtension
                invoke-virtual { v0 }, $build
                move-result-object v0
                return-object v0
            """
        )

        implementExtensionMethod(
            "createQueueAddCommand",
            registerCount = 3,
            """
                sget-object v0, $queueAddEndpointDefaultInstanceField
                invoke-static { v0, p0 }, $messageClass->parseFrom($messageClass[B)$messageClass
                move-result-object v0
                sget-object v1, $commandDefaultInstanceField
                invoke-virtual { v1 }, $messageClass->createBuilder()$messageBuilderClass
                move-result-object v1
                check-cast v1, $extendableBuilderClass
                sget-object v2, $queueAddEndpointExtensionField
                invoke-virtual { v1, v2, v0 }, $setExtension
                invoke-virtual { v1 }, $build
                move-result-object v0
                return-object v0
            """
        )

        // endregion

        CommandResolverFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->overrideCommand(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $commandClass
            """
        )
    }
}

private fun Instruction.fieldReference(opcode: Opcode) =
    if (this.opcode == opcode) (this as ReferenceInstruction).reference as FieldReference else null

private fun Instruction.methodReference() = (this as ReferenceInstruction).reference as MethodReference

private fun BytecodePatchContext.findMethodInHierarchy(type: String, predicate: (Method) -> Boolean): Method {
    var classDef: ClassDef? = classDefBy(type)
    while (classDef != null) {
        classDef.methods.firstOrNull(predicate)?.let { return it }
        classDef = classDef.superclass?.let { classDefByOrNull(it) }
    }
    throw PatchException("Could not find method in class hierarchy of: $type")
}

/**
 * Replaces the body of a static stub method of the extension class.
 *
 * @param registerCount Number of registers used besides the parameter registers.
 */
private fun BytecodePatchContext.implementExtensionMethod(
    name: String,
    registerCount: Int,
    smali: String,
) {
    mutableClassDefBy(EXTENSION_CLASS).apply {
        val stub = methods.first { it.name == name }
        methods.remove(stub)

        val parameterRegisters = stub.parameterTypes.sumOf {
            val type = it.toString()
            if (type == "J" || type == "D") 2L else 1L
        }.toInt()
        methods.add(
            ImmutableMethod(
                type,
                stub.name,
                stub.parameters.map { ImmutableMethodParameter(it.type, null, it.name) },
                stub.returnType,
                stub.accessFlags,
                null,
                null,
                MutableMethodImplementation(registerCount + parameterRegisters),
            ).toMutable().apply {
                addInstructionsWithLabels(0, smali)
            }
        )
    }
}
