/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5441
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.cast

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_47_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger

private const val EXPLAINED_LIST_PREFERENCE =
    "app.morphe.extension.youtube.settings.preference.ExplainedListPreference"
private const val EXTENSION_SERVICE = "app.morphe.extension.youtube.cast.CastAdFreeService"
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/CastPatch;"
private const val EXTENSION_LOUNGE_CLASS = "Lapp/morphe/extension/youtube/cast/CastMenuLounge;"
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

@Suppress("unused")
val castPatch = patch(
    name = "Casting",
    description = "Adds options to keep the Cast session connected when selecting a different video " +
            "or closing the player, and to cast without ads. Casting without ads requires YouTube 21.23 or newer."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        // The streams are fetched like the streams of in-app downloads.
        authHookPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val castWithoutAds = is_21_23_or_greater

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_cast_screen",
                sorting = Sorting.UNSORTED,
                preferences = buildSet {
                    add(SwitchPreference("morphe_restore_old_cast_behavior", summary = true))
                    if (castWithoutAds) {
                        add(SwitchPreference("morphe_cast_ad_free_cast_menu", summary = true))
                        add(ListPreference("morphe_cast_ad_free_quality", tag = EXPLAINED_LIST_PREFERENCE))
                        add(ListPreference("morphe_cast_ad_free_codecs", tag = EXPLAINED_LIST_PREFERENCE))
                    }
                }
            )
        )

        // region Server flags that disconnect the Cast session.

        // The flag is also turned off while casting without ads, so a newly selected video
        // is sent to the device, as with a real screen, instead of played on the phone.
        CastDisconnectOnVideoSelectFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->disconnectCastOnVideoSelect(Z)Z"
            )
        }

        if (is_21_28_or_greater) {
            CastDisconnectOnPlayerCloseFeatureFlagFingerprint.matchAllMethodIndicesForEach { index ->
                insertLiteralOverride(index, "$EXTENSION_CLASS->disconnectCastOnPlayerClose(Z)Z")
            }
        }

        // endregion

        if (!castWithoutAds) {
            Logger.getLogger(this::class.java.name).warning(
                "Cast without ads requires YouTube 21.23 or newer"
            )
            return@execute
        }

        // The service that plays the videos on the Cast devices.
        // The app already declares the permissions for a media playback foreground service.
        resourceXml("AndroidManifest.xml").use { manifest ->
            // Android 17 lets apps find and connect to devices of the local network only with this permission.
            // Older app versions do not declare it.
            if (manifest.elements("uses-permission").none {
                    it.attribute("android:name") == LOCAL_NETWORK_PERMISSION
                }
            ) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", LOCAL_NETWORK_PERMISSION)
                }
            }

            manifest.elements("application").first().appendChild("service") {
                setAttribute("android:name", EXTENSION_SERVICE)
                setAttribute("android:exported", "false")
                setAttribute("android:foregroundServiceType", "mediaPlayback")
            }
        }

        // The Cast devices are listed in the app's own Cast menu, and their sessions connect to a fake lounge
        // that plays with the Cast without ads service.

        // region Screens of the Cast menu.

        val screenType = ScreenConstructorFingerprint.method.definingClass
        val screenInfoType = ScreenConstructorFingerprint.method.parameterTypes.first().toString()
        val screenInfoConstructor = ScreenInfoConstructorFingerprint.method
        val (pairingInfoType, screenIdType, loungeDeviceIdType) = screenInfoConstructor.parameterTypes
            .let { types -> Triple(types[0].toString(), types[2].toString(), types[3].toString()) }

        val screenInfoField = classDefBy(screenType).fields.single { field ->
            field.type == screenInfoType
        }
        val loungeDeviceIdField = classDefBy(screenInfoType).fields.single { field ->
            field.type == loungeDeviceIdType
        }

        ScreenConstructorFingerprint.classDef.addPatchInterface(
            EXTENSION_SCREEN_INTERFACE,
            "patch_getScreenInfo",
            screenInfoField
        )

        // The id classes return their value as the string.
        ScreenInfoConstructorFingerprint.classDef.addPatchInterface(
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

        ScreenManagerConstructorFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.last().index,
                "invoke-static { p0 }, $EXTENSION_LOUNGE_CLASS->setScreenManager($EXTENSION_SCREEN_MANAGER_INTERFACE)V"
            )
        }

        val removeScreenMethod = ScreenManagerRemoveScreenFingerprint.method

        ScreenManagerFingerprint.classDef.addPatchInterface(
            EXTENSION_SCREEN_MANAGER_INTERFACE,
            "patch_removeScreen",
            removeScreenMethod
        )

        removeScreenMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                iget-object v0, v0, $screenInfoField
                invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->keepScreen($EXTENSION_SCREEN_INFO_INTERFACE)Z
                move-result v0
                if-eqz v0, :original
                return-void
                :original
                nop
            """
        )

        // The media route provider of the screens builds its routes from the screens of the screen manager.
        // The routes of paired screens have "YouTube" as their description, which the Cast menu shows as
        // "Playing YouTube". Our screens get no description, like the other Cast devices.
        MediaRouteProviderRoutesFingerprint.let {
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
                    invoke-static { v$screenRegister, v$descriptionRegister }, $EXTENSION_LOUNGE_CLASS->getRouteDescription(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$descriptionRegister
                """
            )
        }

        val discoveryRequestBundleField = MediaRouteDiscoveryRequestConstructorFingerprint.classDef.fields.single { field ->
            field.type == "Landroid/os/Bundle;"
        }

        // The Cast devices are found while the app finds its own devices: while its Cast menu is open.
        MediaRouteProviderDiscoveryRequestFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                if-eqz v0, :no_request
                iget-object v0, v0, $discoveryRequestBundleField
                goto :request
                :no_request
                const/4 v0, 0x0
                :request
                invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->onRouteDiscoveryRequest(Landroid/os/Bundle;)V
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

        val connectParamsField = CloudSessionConnectFingerprint.instructionMatches.last().fieldReference
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
                    invoke-static { v0, v1 }, $EXTENSION_LOUNGE_CLASS->onSessionConnect($EXTENSION_CLOUD_CHANNEL_INTERFACE$EXTENSION_SCREEN_INFO_INTERFACE)V
                """
            )
        }

        if (is_21_47_or_greater) {
            CloudChannelConnectFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->isOurChannel(Ljava/lang/Object;)Z
                    move-result v1
                    if-eqz v1, :original
                    move-object/from16 v1, p1
                    if-eqz v1, :connect
                    iput-object v1, v0, $listenerField
                    :connect
                    invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->connect(Ljava/lang/Object;)V
                    return-void
                    :original
                    nop
                """
            )
        } else {
            // The hook is in connect(), which setListener(listener) calls.
            CloudChannelSetListenerFingerprint.instructionMatches.first().getMethodCalled().addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->isOurChannel(Ljava/lang/Object;)Z
                    move-result v1
                    if-eqz v1, :original
                    invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->connect(Ljava/lang/Object;)V
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
                invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->state(Ljava/lang/Object;)I
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
                invoke-static { v0, v1, v2 }, $EXTENSION_LOUNGE_CLASS->send(Ljava/lang/Object;Ljava/lang/Enum;Ljava/util/Map;)Lcom/google/common/util/concurrent/ListenableFuture;
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
                invoke-static { v0 }, $EXTENSION_LOUNGE_CLASS->disconnect(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                return-void
                :original
                nop
            """
        )

        // endregion
    }
}
