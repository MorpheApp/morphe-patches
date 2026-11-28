/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.castadfree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.string
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_47_or_greater
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/cast/CastMenuLounge;"
private const val EXTENSION_SCREEN_MANAGER_INTERFACE = "Lapp/morphe/extension/youtube/cast/CastMenuLounge\$ScreenManagerInterface;"
private const val EXTENSION_SCREEN_INTERFACE = "Lapp/morphe/extension/youtube/cast/CastMenuLounge\$ScreenInterface;"
private const val EXTENSION_SCREEN_INFO_INTERFACE = "Lapp/morphe/extension/youtube/cast/CastMenuLounge\$ScreenInfoInterface;"
private const val EXTENSION_CLOUD_CHANNEL_INTERFACE = "Lapp/morphe/extension/youtube/cast/CastMenuLounge\$CloudChannelInterface;"

/**
 * Defaults mask of the synthetic constructor of the screen information, for its parameters
 * at index 4 to 7: the list, the two optional ids and the lounge token.
 */
private const val SCREEN_INFO_DEFAULTS_MASK = 0xF0

private const val MAP_TYPE = "Ljava/util/Map;"

/**
 * Smali that loads the map of the parameters in the register, or null.
 */
private fun loadParamsMap(fields: List<FieldReference>, register: String, label: String) =
    fields.joinToString("\n") { field ->
        """
            if-eqz $register, :${label}_null
            iget-object $register, $register, $field
        """
    } + """
        goto :$label
        :${label}_null
        const/4 $register, 0x0
        :$label
        nop
    """

/**
 * Lists Cast devices in the app's own Cast menu, and connects their sessions to a fake lounge
 * that plays with the Cast without ads service.
 */
internal fun hookCastMenuLounge() {
    // region Screens of the Cast menu.

    val screenType = ScreenManagerAddScreenFingerprint.method.parameterTypes.first().toString()
    val screenInfoType = Fingerprint(
        definingClass = screenType,
        name = "<init>",
        parameters = listOf("L", "Z"),
    ).method.parameterTypes.first().toString()

    // screenInfo(pairingInfo, name, screenId, loungeDeviceId, list, id, id, loungeToken, defaultsMask)
    val screenInfoConstructor = Fingerprint(
        definingClass = screenInfoType,
        name = "<init>",
        parameters = listOf("L", "Ljava/lang/String;", "L", "L", "Ljava/util/List;", "L", "L", "L", "I"),
    ).method
    val (pairingInfoType, screenIdType, loungeDeviceIdType) = screenInfoConstructor.parameterTypes
        .let { types -> Triple(types[0].toString(), types[2].toString(), types[3].toString()) }

    val screenInfoField = classDefBy(screenType).fields.single { field ->
        field.type == screenInfoType
    }
    val loungeDeviceIdField = classDefBy(screenInfoType).fields.single { field ->
        field.type == loungeDeviceIdType
    }

    mutableClassDefBy(screenType).addPatchInterface(
        EXTENSION_SCREEN_INTERFACE,
        "patch_getScreenInfo",
        """
            iget-object v0, p0, $screenInfoField
            return-object v0
        """
    )

    // The id classes return their value as the string.
    mutableClassDefBy(screenInfoType).addPatchInterface(
        EXTENSION_SCREEN_INFO_INTERFACE,
        "patch_getLoungeDeviceId",
        """
            iget-object v0, p0, $loungeDeviceIdField
            if-eqz v0, :none
            invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """
    )

    ScreenManagerFingerprint.classDef.addPatchInterface(
        EXTENSION_SCREEN_MANAGER_INTERFACE,
        "patch_addScreen",
        """
            # The parameters are above v15, out of reach of most instructions: move them down first.
            # screenInfo(pairingInfo, name, screenId, loungeDeviceId, null, null, null, null, mask) in v4 .. v13.
            move-object/from16 v6, p1
            move-object/from16 v2, p2
            move-object/from16 v3, p3
            const/4 v1, 0x0
            new-instance v5, $pairingInfoType
            invoke-direct { v5, v1, v1 }, $pairingInfoType-><init>(IZ)V
            new-instance v7, $screenIdType
            invoke-direct { v7, v2 }, $screenIdType-><init>(Ljava/lang/String;)V
            new-instance v8, $loungeDeviceIdType
            invoke-direct { v8, v3 }, $loungeDeviceIdType-><init>(Ljava/lang/String;)V
            const/4 v9, 0x0
            const/4 v10, 0x0
            const/4 v11, 0x0
            const/4 v12, 0x0
            const/16 v13, $SCREEN_INFO_DEFAULTS_MASK
            new-instance v4, $screenInfoType
            invoke-direct/range { v4 .. v13 }, $screenInfoConstructor
            new-instance v0, $screenType
            invoke-direct { v0, v4, v1 }, $screenType-><init>(${screenInfoType}Z)V
            move-object/from16 v2, p0
            invoke-virtual { v2, v0 }, ${ScreenManagerAddScreenFingerprint.method}
            return-object v0
        """
    )

    ScreenManagerConstructorFingerprint.method.apply {
        addInstruction(
            implementation!!.instructions.size - 1,
            "invoke-static { p0 }, $EXTENSION_CLASS->setScreenManager($EXTENSION_SCREEN_MANAGER_INTERFACE)V"
        )
    }

    // removeScreen(screen)
    val removeScreenMethod = Fingerprint(
        definingClass = ScreenManagerFingerprint.classDef.type,
        returnType = "V",
        parameters = listOf(screenType),
        filters = listOf(
            methodCall(
                definingClass = COPY_ON_WRITE_ARRAY_LIST,
                name = "remove"
            ),
            methodCall(
                definingClass = COPY_ON_WRITE_ARRAY_LIST,
                name = "remove",
                location = MatchAfterWithin(5)
            )
        )
    ).method

    ScreenManagerFingerprint.classDef.addPatchInterface(
        EXTENSION_SCREEN_MANAGER_INTERFACE,
        "patch_removeScreen",
        """
            check-cast p1, $screenType
            invoke-virtual { p0, p1 }, $removeScreenMethod
            return-void
        """
    )

    removeScreenMethod.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            iget-object v0, v0, $screenInfoField
            invoke-static { v0 }, $EXTENSION_CLASS->keepScreen($EXTENSION_SCREEN_INFO_INTERFACE)Z
            move-result v0
            if-eqz v0, :original
            return-void
            :original
            nop
        """
    )

    // The Cast devices are found while the app finds its own devices: while its Cast menu is open.
    // The media route provider of the screens builds its routes from the screens of the screen manager.
    val routesFingerprint = Fingerprint(
        returnType = "L",
        parameters = listOf(),
        filters = listOf(
            fieldAccess(
                opcode = Opcode.IGET_OBJECT,
                definingClass = ScreenManagerFingerprint.classDef.type,
                type = COPY_ON_WRITE_ARRAY_LIST
            ),
            // The screen of the route: the next element of the iterator over the screens, cast to its class.
            methodCall(
                definingClass = "Ljava/util/Iterator;",
                name = "next"
            ),
            opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(5)),
            string("YouTube"),
            // The route builder, and its description setter.
            opcode(Opcode.NEW_INSTANCE, location = MatchAfterWithin(5)),
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                returnType = "V",
                parameters = listOf("Ljava/lang/String;")
            )
        )
    )
    val routeProviderType = routesFingerprint.classDef.type

    // The routes of paired screens have "YouTube" as their description, which the Cast menu shows as
    // "Playing YouTube". Our screens get no description, like the other Cast devices.
    routesFingerprint.let {
        val matches = it.instructionMatches
        val screenRegister = matches[2].getInstruction<OneRegisterInstruction>().registerA
        val descriptionIndex = matches.last().index
        val descriptionRegister = matches.last().getInstruction<FiveRegisterInstruction>().registerD

        // Instructions with higher registers do not compile.
        if (screenRegister > 15 || descriptionRegister > 15) {
            throw PatchException("Unexpected registers of the route description")
        }
        it.method.addInstructions(
            descriptionIndex,
            """
                invoke-static { v$screenRegister, v$descriptionRegister }, $EXTENSION_CLASS->getRouteDescription(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$descriptionRegister
            """
        )
    }

    val discoveryRequestType = MediaRouteDiscoveryRequestConstructorFingerprint.classDef.type
    val discoveryRequestBundleField = MediaRouteDiscoveryRequestConstructorFingerprint.classDef.fields.single { field ->
        field.type == "Landroid/os/Bundle;"
    }

    // onDiscoveryRequestChanged(request)
    Fingerprint(
        definingClass = routeProviderType,
        returnType = "V",
        parameters = listOf(discoveryRequestType),
    ).method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            if-eqz v0, :no_request
            iget-object v0, v0, $discoveryRequestBundleField
            goto :request
            :no_request
            const/4 v0, 0x0
            :request
            invoke-static { v0 }, $EXTENSION_CLASS->onRouteDiscoveryRequest(Landroid/os/Bundle;)V
        """
    )

    // endregion

    // region Cloud channel of the session of a screen.

    val channelClass = CloudChannelTerminateFingerprint.classDef
    val channelType = channelClass.type

    // The listener of the channel is the message handler of the cloud session.
    val listenerType = CloudSessionMessageHandlerFingerprint.classDef.interfaces.single()
    val listenerMethod = classDefBy(listenerType).methods.single()
    val messageType = listenerMethod.parameterTypes.single().toString()
    val listenerField = channelClass.fields.single { field ->
        field.type == listenerType
    }

    val (methodEnumType, paramsType) = CloudChannelSendFingerprint.method.parameterTypes
        .let { types -> types[0].toString() to types[1].toString() }
    // The fields from the parameters to their map. Newer app versions wrap the map in another class.
    val paramsMapFields = buildList {
        var type = paramsType
        while (type != MAP_TYPE) {
            val field = classDefBy(type).instanceFields.single()
            add(field)
            type = field.type
        }
    }

    // The session sets the connect parameters of the channel before it connects.
    val connectParamsField = CloudSessionConnectFingerprint.method.let { method ->
        method.getInstruction<ReferenceInstruction>(
            method.indexOfFirstInstructionOrThrow(
                fieldAccess(
                    opcode = Opcode.IPUT_OBJECT,
                    definingClass = channelType
                )
            )
        ).reference as FieldReference
    }
    val connectParamsParamsField = classDefBy(connectParamsField.type).fields.single { field ->
        field.type == paramsType
    }

    channelClass.addPatchInterface(
        EXTENSION_CLOUD_CHANNEL_INTERFACE,
        "patch_receiveMessage",
        """
            iget-object v0, p0, $listenerField
            if-eqz v0, :done
            const-class v1, $methodEnumType
            invoke-static { v1, p1 }, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
            move-result-object v1
            check-cast v1, $methodEnumType
            new-instance v2, $messageType
            invoke-direct { v2, v1, p2 }, $messageType-><init>(${methodEnumType}Lorg/json/JSONObject;)V
            invoke-interface { v0, v2 }, $listenerMethod
            :done
            return-void
        """
    )

    channelClass.addPatchInterface(
        EXTENSION_CLOUD_CHANNEL_INTERFACE,
        "patch_getConnectParams",
        """
            iget-object v0, p0, $connectParamsField
            if-eqz v0, :none
            iget-object v0, v0, $connectParamsParamsField
            ${loadParamsMap(paramsMapFields, "v0", "params_map")}
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """
    )

    // Mark the channel of the session of one of our screens.
    // The hooks are at the start of the methods, where all local registers are free. The parameters are
    // moved to the low registers, as large methods have parameter registers above v15.
    CloudSessionConnectFingerprint.let {
        val sessionChannelField = it.classDef.fields.single { field ->
            field.type == channelType
        }

        it.method.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $sessionChannelField
                move-object/from16 v1, p1
                invoke-static { v0, v1 }, $EXTENSION_CLASS->onSessionConnect($EXTENSION_CLOUD_CHANNEL_INTERFACE$EXTENSION_SCREEN_INFO_INTERFACE)V
            """
        )
    }

    // The flag exists in 21.30 and newer (not in 21.28).
    if (is_21_30_or_greater) {
        // A video opened while casting to our device is sent to the device, as with a real screen,
        // instead of played on the phone.
        PlayOnPhoneFlagFingerprint.let {
            it.method.apply {
                val moveResult = it.instructionMatches.last()
                val register = getInstruction<OneRegisterInstruction>(moveResult.index).registerA

                addInstructions(
                    moveResult.index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->playNewVideoOnPhone(Z)Z
                        move-result v$register
                    """
                )
            }
        }
    }

    if (is_21_47_or_greater) {
        // connect(listener, reason) sets the listener and connects.
        Fingerprint(
            definingClass = channelType,
            returnType = "V",
            parameters = listOf(listenerType, "I"),
        ).method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                invoke-static { v0 }, $EXTENSION_CLASS->isOurChannel(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :original
                move-object/from16 v1, p1
                if-eqz v1, :connect
                iput-object v1, v0, $listenerField
                :connect
                invoke-static { v0 }, $EXTENSION_CLASS->connect(Ljava/lang/Object;)V
                return-void
                :original
                nop
            """
        )
    } else {
        // setListener(listener) sets the listener and calls connect().
        Fingerprint(
            definingClass = channelType,
            returnType = "V",
            parameters = listOf(listenerType),
            filters = listOf(
                methodCall(
                    definingClass = "this",
                    returnType = "V",
                    parameters = listOf()
                )
            )
        ).instructionMatches.first().getMethodCalled().addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                invoke-static { v0 }, $EXTENSION_CLASS->isOurChannel(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :original
                invoke-static { v0 }, $EXTENSION_CLASS->connect(Ljava/lang/Object;)V
                return-void
                :original
                nop
            """
        )
    }

    CloudChannelStateFingerprint.method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            invoke-static { v0 }, $EXTENSION_CLASS->state(Ljava/lang/Object;)I
            move-result v0
            if-ltz v0, :original
            return v0
            :original
            nop
        """
    )

    CloudChannelSendFingerprint.method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            move-object/from16 v2, p2
            ${loadParamsMap(paramsMapFields, "v2", "send")}
            invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->send(Ljava/lang/Object;Ljava/lang/Enum;Ljava/util/Map;)Lcom/google/common/util/concurrent/ListenableFuture;
            move-result-object v0
            if-eqz v0, :original
            return-object v0
            :original
            nop
        """
    )

    CloudChannelDisconnectFingerprint.method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            invoke-static { v0 }, $EXTENSION_CLASS->disconnect(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :original
            return-void
            :original
            nop
        """
    )

    // endregion
}
