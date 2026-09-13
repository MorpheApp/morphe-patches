/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2518
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.folderOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.all.misc.clone.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.fix.bitmap.fixRecycledBitmapPatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.util.resource.StringResourceSanitizer
import app.morphe.util.ResourceGroup
import app.morphe.util.asSequence
import app.morphe.util.copyResources
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.removeFromParent
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.util.logging.Logger

private val mipmapDirectories = mapOf(
    // Target app does not have ldpi icons.
    "mipmap-mdpi" to "108x108 px",
    "mipmap-hdpi" to "162x162 px",
    "mipmap-xhdpi" to "216x216 px",
    "mipmap-xxhdpi" to "324x324 px",
    "mipmap-xxxhdpi" to "432x432 px"
)

private val iconStyleNames = arrayOf(
    "black",
    "dark",
    "light",
    "play",
    "play_black",
)

private const val ORIGINAL_USER_ICON_STYLE_NAME = "original"
private const val CUSTOM_USER_ICON_STYLE_NAME = "custom"

// The icon the app uses when no icon style was selected.
private const val DEFAULT_ICON_STYLE_NAME = "black"

// Derived from the bundled styles so a new style cannot be missing here.
private val iconStyleValues = buildMap {
    put("App default", null)
    put("Original", ORIGINAL_USER_ICON_STYLE_NAME)

    iconStyleNames.forEach { style ->
        put(style.replace('_', ' ').replaceFirstChar(Char::uppercase), style)
    }

    put("Custom", CUSTOM_USER_ICON_STYLE_NAME)
}

private const val LAUNCHER_RESOURCE_NAME_PREFIX = "morphe_launcher_"
private const val LAUNCHER_ADAPTIVE_BACKGROUND_PREFIX = "morphe_adaptive_background_"
private const val LAUNCHER_ADAPTIVE_FOREGROUND_PREFIX = "morphe_adaptive_foreground_"
private const val LAUNCHER_ADAPTIVE_MONOCHROME_PREFIX = "morphe_adaptive_monochrome"
private const val NOTIFICATION_ICON_NAME = "morphe_notification_icon"

private val USER_CUSTOM_ADAPTIVE_FILE_NAMES = arrayOf(
    "$LAUNCHER_ADAPTIVE_BACKGROUND_PREFIX$CUSTOM_USER_ICON_STYLE_NAME.png",
    "$LAUNCHER_ADAPTIVE_FOREGROUND_PREFIX$CUSTOM_USER_ICON_STYLE_NAME.png"
)

private const val USER_CUSTOM_MONOCHROME_FILE_NAME = "${LAUNCHER_ADAPTIVE_MONOCHROME_PREFIX}_$CUSTOM_USER_ICON_STYLE_NAME.xml"

// Custom notification icon can be provided as an XML vector drawable or a PNG raster image.
private const val USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME = "${NOTIFICATION_ICON_NAME}_$CUSTOM_USER_ICON_STYLE_NAME.xml"
private const val USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME = "${NOTIFICATION_ICON_NAME}_$CUSTOM_USER_ICON_STYLE_NAME.png"

// Drawable DPI directories for PNG notification icons.
private val notificationIconPngDirectories = mapOf(
    "drawable-mdpi" to "24x24 px",
    "drawable-hdpi" to "36x36 px",
    "drawable-xhdpi" to "48x48 px",
    "drawable-xxhdpi" to "72x72 px",
    "drawable-xxxhdpi" to "96x96 px",
)

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/CustomBrandingPatch;"

// Must be named in the 'app.morphe' namespace, otherwise the log is not published.
private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

/**
 * Shared custom branding patch for YouTube and YT Music.
 */
internal fun baseCustomBrandingPatch(
    originalLauncherIconName: String,
    originalNotificationIconName: String,
    originalAppName: String,
    originalAppPackageName: String,
    isYouTubeMusic: Boolean,
    numberOfPresetAppNames: Int,
    mainActivityOnCreateFingerprint: Fingerprint,
    mainActivityName: String,
    activityAliasNameWithIntents: String,
    preferenceScreen: BasePreferenceScreen.Screen,
    block: ResourcePatchBuilder.() -> Unit,
    executeBlock: ResourcePatchContext.() -> Unit = {}
): ResourcePatch = resourcePatch(
    name = "Custom branding",
    description = "Adds options to change the app icon and app name. " +
            "For mounted (root) installations the branding is applied while patching, " +
            "because it cannot be changed from the app settings."
) {

    availability { installer, _ ->
        when (installer) {
            InstallerType.MOUNT -> PatchAvailability.DISABLED
            else -> PatchAvailability.ENABLED
        }
    }

    val customName by stringOption(
        key = "customName",
        title = "App name",
        description = "Custom app name."
    )

    val customIcon by folderOption(
        key = "customIcon",
        title = "Custom icon",
        description = """
            Folder with images to use as a custom icon.
            
            The folder must contain one or more of the following folders, depending on the DPI of the device:
            ${mipmapDirectories.keys.joinToString("\n") { "- $it" }}
            
            Each of the folders must contain all of the following files:
            ${USER_CUSTOM_ADAPTIVE_FILE_NAMES.joinToString("\n")}
            
            The image dimensions must be as follows:
            ${mipmapDirectories.map { (dpi, dim) -> "- $dpi: $dim" }.joinToString("\n")}

            Optionally, the path contains a 'drawable' folder with a monochrome icon file:
            $USER_CUSTOM_MONOCHROME_FILE_NAME

            Optionally, the path contains a notification icon in one of the following formats:
            - XML vector drawable placed in 'drawable':
              $USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME
            - PNG raster images placed in the matching 'drawable-<dpi>' folders:
              ${notificationIconPngDirectories.map { (dpi, dim) -> "- $dpi/$USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME ($dim)" }.joinToString("\n")}
        """
    )

    val appIconStyle by stringOption(
        key = "appIcon",
        values = iconStyleValues,
        title = "App icon",
        description = """
            The app icon to use.

            A regular installation starts with this icon and can change it later in the app
            settings. A mounted (root) installation cannot change it from the app settings,
            so changing its icon requires patching again.

            'App default' uses the custom icon if one is provided, and otherwise the standard icon.

            The notification icon of the app follows this option.

            Places that cannot use the icon selected in the app settings, such as Android Auto and
            the app list of the system settings, always show the icon chosen here.
        """
    ) {
        it == null || iconStyleValues.containsValue(it)
    }

    block()

    dependsOn(
        resourceMappingPatch,
        fixRecycledBitmapPatch,
        bytecodePatch {
            execute {
                mainActivityOnCreateFingerprint.method.addInstruction(
                    0,
                    "invoke-static { }, $EXTENSION_CLASS->setBranding()V"
                )

                NumberOfPresetAppNamesExtensionFingerprint.method.returnEarly(numberOfPresetAppNames)
                UserProvidedCustomNameExtensionFingerprint.method.returnEarly(customName != null)
                UserProvidedCustomIconExtensionFingerprint.method.returnEarly(customIcon != null)
                OriginalLauncherIconNameExtensionFingerprint.method.returnEarly(originalLauncherIconName)
                OriginalNotificationIconNameExtensionFingerprint.method.returnEarly(originalNotificationIconName)

                NotificationBuilderFingerprint.let {
                    it.method.apply {
                        mapOf(
                            2 to "getColor",
                            0 to "getSmallIcon"
                        ).forEach { (offset, methodName) ->
                            val index = it.instructionMatches[offset].index
                            val register = getInstruction<FiveRegisterInstruction>(index).registerD

                            addInstructions(
                                index,
                                """
                                    invoke-static { v$register }, $EXTENSION_CLASS->$methodName(I)I
                                    move-result v$register                                
                                """
                            )
                        }
                    }
                }

                NotificationIconFingerprint.let {
                    it.method.apply {
                        val index = it.instructionMatches.last().index
                        val register = getInstruction<TwoRegisterInstruction>(index).registerA

                        addInstructions(
                            index,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->getSmallIcon(I)I
                                move-result v$register                                
                            """
                        )
                    }
                }
            }

            finalize {
                val iconStyle = resolveIconStyle(appIconStyle, customIcon)

                // The icon the app starts with, before the user selects one in the app settings.
                DefaultIconStyleNameExtensionFingerprint.method.returnEarly(iconStyle)

                val isMounted = isMountedInstall(originalAppPackageName)

                MountedIconAppliedExtensionFingerprint.method.returnEarly(
                    isMounted && iconStyle != ORIGINAL_USER_ICON_STYLE_NAME
                )

                // The replaced notification icon does not match the tint of the original icon.
                MountedNotificationIconAppliedExtensionFingerprint.method.returnEarly(
                    isMounted && hasMountedNotificationIcon(iconStyle, customIcon)
                )
            }
        },
        resourcePatch {
            finalize {
                val useCustomName = customName != null

                // The UI preferences cannot be selectively added here, because the settings finalize
                // block may have already run and the settings are already wrote to file.
                // Instead, the non-functional in-app settings are removed on app startup by extension code.
                if (isMountedInstall(originalAppPackageName)) {
                    // A mounted install keeps the original app installed and the system parses the
                    // manifest of the stock APK, so the launch aliases and any manifest branding
                    // attribute are never used. Resource ids are unchanged by patching and are still
                    // resolved from the mounted APK, and replacing what those ids point to is the only
                    // way the branding of a mounted install can be changed.
                    applyMountedBranding(
                        originalLauncherIconName,
                        originalNotificationIconName,
                        originalAppName,
                        appIconStyle,
                        customIcon,
                        customName
                    )

                    return@finalize
                }

                document("AndroidManifest.xml").use { document ->
                    // Create launch aliases that can be programmatically selected in app.
                    fun createAlias(
                        aliasName: String,
                        iconMipmapName: String,
                        appNameIndex: Int,
                        useCustomName: Boolean,
                        enabled: Boolean,
                        intents: NodeList
                    ): Element {
                        val label = if (useCustomName) {
                            if (customName == null) {
                                "Custom" // Dummy text, and normally cannot be seen.
                            } else {
                                customName!!
                            }
                        } else if (appNameIndex == 1) {
                            // Indexing starts at 1.
                            originalAppName
                        } else {
                            "@string/morphe_custom_branding_name_entry_$appNameIndex"
                        }
                        val alias = document.createElement("activity-alias")
                        alias.setAttribute("android:name", aliasName)
                        alias.setAttribute("android:enabled", enabled.toString())
                        alias.setAttribute("android:exported", "true")
                        alias.setAttribute("android:icon", "@mipmap/$iconMipmapName")
                        alias.setAttribute("android:label", label)
                        alias.setAttribute("android:targetActivity", mainActivityName)

                        // Copy all intents from the original alias so long press actions still work.
                        if (isYouTubeMusic) {
                            val intentFilter = document.createElement("intent-filter").apply {
                                val action = document.createElement("action")
                                action.setAttribute("android:name", "android.intent.action.MAIN")
                                appendChild(action)

                                val category = document.createElement("category")
                                category.setAttribute("android:name", "android.intent.category.LAUNCHER")
                                appendChild(category)
                            }
                            alias.appendChild(intentFilter)
                        } else {
                            for (i in 0 until intents.length) {
                                alias.appendChild(
                                    intents.item(i).cloneNode(true)
                                )
                            }
                        }

                        return alias
                    }

                    val application = document.getElementsByTagName("application").item(0) as Element
                    val intentFilters = document.childNodes.findElementByAttributeValueOrThrow(
                        "android:name",
                        activityAliasNameWithIntents
                    ).childNodes

                    val enabledNameIndex = if (useCustomName) numberOfPresetAppNames else 1 // 1 indexing
                    val enabledIconStyle = resolveIconStyle(appIconStyle, customIcon)

                    // The application icon ('static' icon) is used wherever a launch alias is not,
                    // such as Android Auto, the app list of the system settings, the push
                    // notification on some devices, and the apk before installing. Leaving it
                    // unpatched is what made those places show the unpatched icon.
                    //
                    // It cannot be selected at runtime, so it stays at the icon chosen while
                    // patching even if a different icon is later selected in the app. The original
                    // icon is restored by patching with the 'Original' icon style.
                    if (enabledIconStyle != ORIGINAL_USER_ICON_STYLE_NAME) {
                        application.setAttribute(
                            "android:icon",
                            "@mipmap/$LAUNCHER_RESOURCE_NAME_PREFIX$enabledIconStyle"
                        )
                    }

                    // The launch aliases only declare 'android:icon', so a launcher that prefers the
                    // round icon of the app keeps showing the unpatched icon. Adaptive icons replaced
                    // round icons in Android 8 and the target apps require Android 8 or later, so the
                    // declaration is removed instead of adding an obsolete icon to every alias.
                    application.removeAttribute("android:roundIcon")

                    for (appNameIndex in 1 .. numberOfPresetAppNames) {
                        fun aliasName(name: String): String = ".morphe_" + name + '_' + appNameIndex

                        val useCustomNameLabel = (useCustomName && appNameIndex == numberOfPresetAppNames)

                        // Original icon.
                        application.appendChild(
                            createAlias(
                                aliasName = aliasName(ORIGINAL_USER_ICON_STYLE_NAME),
                                iconMipmapName = originalLauncherIconName,
                                appNameIndex = appNameIndex,
                                useCustomName = useCustomNameLabel,
                                enabled = appNameIndex == enabledNameIndex &&
                                        enabledIconStyle == ORIGINAL_USER_ICON_STYLE_NAME,
                                intentFilters
                            )
                        )

                        // Bundled icons.
                        iconStyleNames.forEach { style ->
                            application.appendChild(
                                createAlias(
                                    aliasName = aliasName(style),
                                    iconMipmapName = LAUNCHER_RESOURCE_NAME_PREFIX + style,
                                    appNameIndex = appNameIndex,
                                    useCustomName = useCustomNameLabel,
                                    enabled = (appNameIndex == enabledNameIndex && style == enabledIconStyle),
                                    intentFilters
                                )
                            )
                        }

                        // User provided custom icon.
                        //
                        // Must add all aliases even if the user did not provide a custom icon of their own.
                        // This is because if the user installs with an option, then repatches without the option,
                        // the alias must still exist because if it was previously enabled, and then it's removed
                        // the app will become broken and cannot launch. Even if the app data is cleared
                        // it still cannot be launched and the only fix is to uninstall the app.
                        // To prevent this, always include all aliases and use dummy data if needed.
                        application.appendChild(
                            createAlias(
                                aliasName = aliasName(CUSTOM_USER_ICON_STYLE_NAME),
                                iconMipmapName = LAUNCHER_RESOURCE_NAME_PREFIX + CUSTOM_USER_ICON_STYLE_NAME,
                                appNameIndex = appNameIndex,
                                useCustomName = useCustomNameLabel,
                                enabled = appNameIndex == enabledNameIndex &&
                                        enabledIconStyle == CUSTOM_USER_ICON_STYLE_NAME,
                                intentFilters
                            )
                        )
                    }

                    // Remove the main action from the original alias, otherwise two apps icons
                    // can be shown in the launcher. Can only be done after adding the new aliases.
                    intentFilters.findElementByAttributeValueOrThrow(
                        "android:name",
                        "android.intent.action.MAIN"
                    ).removeFromParent()

                    application.setAttribute(
                        "android:label",
                        if (useCustomName) {
                            // Use custom name everywhere.
                            customName!!
                        } else {
                            // The YT application name can appear in some places alongside the system
                            // YouTube app, such as the settings app list and in the "open with" file picker.
                            // Because the YouTube app cannot be completely uninstalled and only disabled,
                            // use a custom name for this situation to disambiguate which app is which.
                            "@string/morphe_custom_branding_name_entry_2"
                        }
                    )
                }
            }
        }
    )

    execute {
        val useCustomName = customName != null
        val useCustomIcon = customIcon != null

        if (appIconStyle == CUSTOM_USER_ICON_STYLE_NAME && !useCustomIcon) {
            throw PatchException("The 'Custom' app icon requires the 'Custom icon' option.")
        }

        val preferences = mutableSetOf<BasePreference>()

        preferences += if (useCustomName) {
            ListPreference(
                key = "morphe_custom_branding_name",
                entriesKey = "morphe_custom_branding_name_custom_entries",
                entryValuesKey = "morphe_custom_branding_name_custom_entry_values"
            )
        } else {
            ListPreference("morphe_custom_branding_name")
        }

        if (useCustomIcon) {
            preferences += ListPreference(
                key = "morphe_custom_branding_icon",
                tag = "app.morphe.extension.shared.settings.preference.IconListPreference",
                entriesKey = "morphe_custom_branding_icon_custom_entries",
                entryValuesKey = "morphe_custom_branding_icon_custom_entry_values"
            )
            preferences += ListPreference(
                key = "morphe_custom_branding_notification_icon",
                tag = "app.morphe.extension.shared.settings.preference.NotificationIconListPreference",
                entriesKey = "morphe_custom_branding_notification_icon_custom_entries",
                entryValuesKey = "morphe_custom_branding_notification_icon_custom_entry_values"
            )
        } else {
            preferences += ListPreference(
                key = "morphe_custom_branding_icon",
                tag = "app.morphe.extension.shared.settings.preference.IconListPreference"
            )
            preferences += ListPreference(
                key = "morphe_custom_branding_notification_icon",
                tag = "app.morphe.extension.shared.settings.preference.NotificationIconListPreference"
            )
        }

        preferenceScreen.addPreferences(noTitleUnsortedPreferenceCategory(preferences))

        iconStyleNames.forEach { style ->
            copyResources(
                "custom-branding",
                ResourceGroup(
                    "drawable",
                    "$LAUNCHER_ADAPTIVE_BACKGROUND_PREFIX$style.xml",
                    "$LAUNCHER_ADAPTIVE_FOREGROUND_PREFIX$style.xml",
                    "${LAUNCHER_ADAPTIVE_MONOCHROME_PREFIX}_$style.xml",
                    "${NOTIFICATION_ICON_NAME}_$style.xml",
                ),
                ResourceGroup(
                    "mipmap-anydpi",
                    "$LAUNCHER_RESOURCE_NAME_PREFIX$style.xml"
                )
            )
        }

        copyResources(
            "custom-branding",
            // Copy template user icon, because the aliases must be added even if no user icon is provided.
            ResourceGroup(
                "drawable",
                USER_CUSTOM_MONOCHROME_FILE_NAME,
                USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME,
            ),
            ResourceGroup(
                "mipmap-anydpi",
                "$LAUNCHER_RESOURCE_NAME_PREFIX$CUSTOM_USER_ICON_STYLE_NAME.xml"
            )
        )

        // Copy template icon files.
        mipmapDirectories.keys.forEach { dpi ->
            copyResources(
                "custom-branding",
                ResourceGroup(
                    dpi,
                    "$LAUNCHER_ADAPTIVE_BACKGROUND_PREFIX$CUSTOM_USER_ICON_STYLE_NAME.png",
                    "$LAUNCHER_ADAPTIVE_FOREGROUND_PREFIX$CUSTOM_USER_ICON_STYLE_NAME.png",
                )
            )
        }

        // Copy the user provided icon files here and not in the finalize block, because a mounted
        // install uses them to replace the icon of the app and that is done while finalizing.
        if (useCustomIcon) {
            // Copy user provided files
            val iconPathFile = File(customIcon!!.trim())

            if (!iconPathFile.exists()) {
                throw PatchException(
                    "The custom icon path cannot be found: " + iconPathFile.absolutePath
                )
            }

            if (!iconPathFile.isDirectory) {
                throw PatchException(
                    "The custom icon path must be a folder: " + iconPathFile.absolutePath
                )
            }

            val resourceDirectory = get("res")
            var copiedFiles = false

            // For each source folder, copy the files to the target resource directories.
            iconPathFile.listFiles {
                    file -> file.isDirectory && file.name in mipmapDirectories
            }!!.forEach { dpiSourceFolder ->
                val targetDpiFolder = resourceDirectory.resolve(dpiSourceFolder.name)
                if (!targetDpiFolder.exists()) {
                    // Should never happen.
                    throw IllegalStateException("Resource not found: $dpiSourceFolder")
                }

                val customFiles = dpiSourceFolder.listFiles { file ->
                    file.isFile && file.name in USER_CUSTOM_ADAPTIVE_FILE_NAMES
                }!!

                if (customFiles.isNotEmpty() && customFiles.size != USER_CUSTOM_ADAPTIVE_FILE_NAMES.size) {
                    throw PatchException("Must include all required icon files " +
                            "but only found " + customFiles.map { it.name })
                }

                customFiles.forEach { imgSourceFile ->
                    val imgTargetFile = targetDpiFolder.resolve(imgSourceFile.name)
                    imgSourceFile.copyTo(target = imgTargetFile, overwrite = true)

                    copiedFiles = true
                }
            }

            // Copy monochrome icon if provided.
            val drawableSourceFolder = iconPathFile.resolve("drawable")
            if (drawableSourceFolder.exists()) {
                val monochromeFile = drawableSourceFolder.resolve(USER_CUSTOM_MONOCHROME_FILE_NAME)
                if (monochromeFile.exists()) {
                    monochromeFile.copyTo(
                        target = resourceDirectory.resolve("drawable/$USER_CUSTOM_MONOCHROME_FILE_NAME"),
                        overwrite = true
                    )
                    copiedFiles = true
                }

                // XML vector notification icon.
                val notificationXml = drawableSourceFolder.resolve(USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME)
                if (notificationXml.exists()) {
                    notificationXml.copyTo(
                        target = resourceDirectory.resolve("drawable/$USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME"),
                        overwrite = true
                    )
                    copiedFiles = true
                }
            }

            // PNG notification icons.
            // If any DPI folder is present, copy what's available.
            iconPathFile.listFiles { file ->
                file.isDirectory && file.name in notificationIconPngDirectories
            }!!.forEach { dpiSourceFolder ->
                val pngFile = dpiSourceFolder.resolve(USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME)
                if (pngFile.exists()) {
                    val targetFolder = resourceDirectory.resolve(dpiSourceFolder.name)
                    if (!targetFolder.exists()) {
                        throw IllegalStateException("Resource directory not found: ${dpiSourceFolder.name}")
                    }
                    pngFile.copyTo(
                        target = targetFolder.resolve(USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME),
                        overwrite = true
                    )
                    copiedFiles = true
                }
            }

            if (!copiedFiles) {
                throw PatchException("Expected to find directories and files: "
                        + USER_CUSTOM_ADAPTIVE_FILE_NAMES.contentToString()
                        + "\nBut none were found in the provided option file path: " + iconPathFile.absolutePath)
            }
        }

        executeBlock()
    }
}

/**
 * Applies the branding of a mounted (root) install by replacing the contents of the resources
 * the manifest of the stock APK already points to.
 *
 * @param originalNotificationIconName The notification icon resource name of the unpatched app.
 * @param originalAppName The app name resource reference declared by the unpatched app.
 * @param appIconStyle The icon style patch option, or null to use the default icon.
 * @param customIcon The custom icon patch option folder, if one was provided.
 * @param customName The custom app name patch option, if one was provided.
 */
private fun ResourcePatchContext.applyMountedBranding(
    originalLauncherIconName: String,
    originalNotificationIconName: String,
    originalAppName: String,
    appIconStyle: String?,
    customIcon: String?,
    customName: String?
) {
    if (customName != null) {
        val resourceNames = appNameResourceNames(originalAppName)

        resourceNames.forEachIndexed { index, resourceName ->
            if (setAppNameResource(resourceName, customName)) return@forEachIndexed

            // The name the patch declares must exist, but a name found in the manifest can
            // point to a resource that is not declared in the default strings file.
            if (index == 0) {
                throw PatchException("Could not find the app name resource: $resourceName")
            }

            logger.warning("Could not find the app name resource: $resourceName")
        }

        logger.info("Mounted install app name: $customName (${resourceNames.joinToString()})")
    }

    val iconStyle = resolveIconStyle(appIconStyle, customIcon)

    // Nothing to replace, the app already uses the original icon.
    if (iconStyle == ORIGINAL_USER_ICON_STYLE_NAME) return

    setLauncherIconResource(originalLauncherIconName, iconStyle)
    logger.info("Mounted install app icon: $iconStyle")

    if (setNotificationIconResource(originalNotificationIconName, iconStyle, customIcon)) {
        logger.info("Mounted install notification icon: $iconStyle")
    }
}

/**
 * The bytes of a branding resource bundled with the patch, or null if it does not exist.
 */
private fun bundledBrandingResource(resourceFile: String): ByteArray? =
    inputStreamFromBundledResource("custom-branding", resourceFile)?.use { it.readBytes() }

/**
 * If the app is installed by mounting (root).
 *
 * The installer type is not reported to patches, so it is inferred from the package name, which
 * only a regular install changes. Other patches change it while executing, so this is accurate
 * only after all patches have executed.
 */
private fun isMountedInstall(originalAppPackageName: String) =
    setOrGetFallbackPackageName(originalAppPackageName) == originalAppPackageName

/**
 * If the notification icon of the app is replaced by the icon style.
 *
 * The bundled custom notification icon is only a placeholder for the launch aliases of a regular
 * install, so a custom style keeps the original icon unless the user provided one of their own.
 */
private fun hasMountedNotificationIcon(iconStyle: String, customIcon: String?): Boolean {
    if (iconStyle == ORIGINAL_USER_ICON_STYLE_NAME) return false
    if (iconStyle != CUSTOM_USER_ICON_STYLE_NAME) return true

    val iconPathFile = File(customIcon!!.trim())

    return iconPathFile.resolve("drawable/$USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME").exists() ||
            notificationIconPngDirectories.keys.any { dpi ->
                iconPathFile.resolve("$dpi/$USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME").exists()
            }
}

/**
 * The resource directories of a type, such as 'drawable' and every 'drawable-<qualifier>'.
 */
private fun ResourcePatchContext.resourceDirectories(resourceType: String): List<File> {
    val directories = get("res").listFiles { file ->
        file.isDirectory && (file.name == resourceType || file.name.startsWith("$resourceType-"))
    } ?: throw PatchException("Could not find the app resources")

    return directories.asList()
}

/**
 * The icon style to use, resolving the 'App default' option value.
 */
private fun resolveIconStyle(appIconStyle: String?, customIcon: String?): String =
    appIconStyle
        ?: if (customIcon != null) CUSTOM_USER_ICON_STYLE_NAME else DEFAULT_ICON_STYLE_NAME

/**
 * The names of the string resources that decide the app name a launcher and the system show.
 * The name the patch declares is first, followed by any additional name the manifest points to.
 */
private fun ResourcePatchContext.appNameResourceNames(originalAppName: String): List<String> {
    val declaredName = stringResourceNameOrNull(originalAppName)
        ?: throw PatchException("Expected a string resource but found: $originalAppName")

    val resourceNames = mutableListOf(declaredName)

    document("AndroidManifest.xml").use { document ->
        // A launcher shows the label of the launch activity and falls back to the application
        // label, and the app list of the system settings always shows the application label.
        // These can be different resources, such as "YT Music" and "YouTube Music".
        val application = document.getElementsByTagName("application").item(0) as Element
        stringResourceNameOrNull(application.getAttribute("android:label"))?.let {
            resourceNames += it
        }

        arrayOf("activity", "activity-alias").forEach { tagName ->
            val elements = document.getElementsByTagName(tagName)

            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                if (!element.isLauncherEntry()) continue

                stringResourceNameOrNull(element.getAttribute("android:label"))?.let {
                    resourceNames += it
                }
            }
        }
    }

    return resourceNames.distinct()
}

/**
 * The resource name of a string reference such as '@string/app_name', or null for anything
 * else such as a literal or a framework resource like '@android:string/untitled'.
 */
private fun stringResourceNameOrNull(reference: String): String? =
    if (reference.startsWith("@string/")) reference.substring("@string/".length) else null

private fun Element.isLauncherEntry(): Boolean =
    getElementsByTagName("category").asSequence()
        .filterIsInstance<Element>()
        .any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }

/**
 * Sets the app name of the default locale, and removes the localized app names because
 * a launcher resolves the app name using the locale of the device and not of the app.
 *
 * @return If the resource was declared and set for the default locale.
 */
private fun ResourcePatchContext.setAppNameResource(resourceName: String, appName: String): Boolean {
    var defaultAppNameSet = false

    resourceDirectories("values").forEach { valuesDirectory ->
        if (!valuesDirectory.resolve("strings.xml").exists()) return@forEach

        document("res/${valuesDirectory.name}/strings.xml").use { document ->
            val resources = document.getElementsByTagName("resources").item(0) ?: return@use
            val declarations = resources.childNodes.asSequence()
                .filterIsInstance<Element>()
                .filter { it.tagName == "string" && it.getAttribute("name") == resourceName }
                .toList()

            if (valuesDirectory.name != "values") {
                declarations.forEach { it.removeFromParent() }
                return@use
            }

            val sanitizedAppName = StringResourceSanitizer.sanitizeAndroidResourceString(
                resourceName,
                appName
            )

            if (declarations.isEmpty()) {
                val declaration = document.createElement("string")
                declaration.setAttribute("name", resourceName)
                declaration.textContent = sanitizedAppName
                resources.appendChild(declaration)
            } else {
                declarations.forEach { it.textContent = sanitizedAppName }
            }

            defaultAppNameSet = true
        }
    }

    return defaultAppNameSet
}

/**
 * Replaces the notification icon of the app with a bundled icon style, or with the
 * notification icon the user provided.
 *
 * The original icons are removed and not overwritten, because they are raster images for each
 * density and a density qualified resource is a better match than the vector that replaces them.
 *
 * @return If the notification icon was replaced.
 */
private fun ResourcePatchContext.setNotificationIconResource(
    originalNotificationIconName: String,
    iconStyle: String,
    customIcon: String?
): Boolean {
    if (!hasMountedNotificationIcon(iconStyle, customIcon)) return false

    val vectorIcon: ByteArray?
    val rasterIcons: Map<String, File>

    if (iconStyle == CUSTOM_USER_ICON_STYLE_NAME) {
        val iconPathFile = File(customIcon!!.trim())

        vectorIcon = iconPathFile
            .resolve("drawable/$USER_CUSTOM_NOTIFICATION_ICON_XML_FILE_NAME")
            .takeIf { it.exists() }
            ?.readBytes()

        rasterIcons = notificationIconPngDirectories.keys.mapNotNull { dpi ->
            val sourceFile = iconPathFile.resolve("$dpi/$USER_CUSTOM_NOTIFICATION_ICON_PNG_FILE_NAME")
            if (sourceFile.exists()) dpi to sourceFile else null
        }.toMap()
    } else {
        vectorIcon = bundledBrandingResource("drawable/${NOTIFICATION_ICON_NAME}_$iconStyle.xml")
            ?: throw PatchException("Could not find the bundled notification icon style: $iconStyle")

        rasterIcons = emptyMap()
    }

    var removedIcons = 0

    resourceDirectories("drawable").forEach { drawableDirectory ->
        drawableDirectory.listFiles { file ->
            file.isFile && file.nameWithoutExtension == originalNotificationIconName
        }?.forEach { iconFile ->
            if (iconFile.delete()) removedIcons++
        }
    }

    if (removedIcons == 0) {
        // Not fatal, because the launcher icon and the app name are the visible part of the
        // branding and patching should not fail if only the notification icon was renamed.
        logger.warning(
            "Could not find the notification icon to replace: @drawable/$originalNotificationIconName"
        )
        return false
    }

    val resourceDirectory = get("res")

    if (vectorIcon != null) {
        val drawableDirectory = resourceDirectory.resolve("drawable")
        drawableDirectory.mkdirs()
        drawableDirectory.resolve("$originalNotificationIconName.xml").writeBytes(vectorIcon)
    }

    rasterIcons.forEach { (dpi, sourceFile) ->
        val targetDirectory = resourceDirectory.resolve(dpi)
        targetDirectory.mkdirs()
        sourceFile.copyTo(
            target = targetDirectory.resolve("$originalNotificationIconName.png"),
            overwrite = true
        )
    }

    return true
}

/**
 * Replaces the adaptive launcher icon of the app with a bundled icon style.
 */
private fun ResourcePatchContext.setLauncherIconResource(
    originalLauncherIconName: String,
    iconStyle: String
) {
    val iconReferences = mutableSetOf("mipmap" to originalLauncherIconName)

    document("AndroidManifest.xml").use { document ->
        val application = document.getElementsByTagName("application").item(0) as Element

        // Some launchers show the round icon, and it can point to a different resource.
        arrayOf("android:icon", "android:roundIcon").forEach { attributeName ->
            val reference = application.getAttribute(attributeName)
            val typeIndex = reference.indexOf('/')

            // Framework resources such as '@android:mipmap/sym_def_app_icon' cannot be replaced.
            if (!reference.startsWith("@") || typeIndex < 0 || reference.contains(':')) {
                return@forEach
            }

            iconReferences += reference.substring(1, typeIndex) to reference.substring(typeIndex + 1)
        }
    }

    val bundledIcon =
        bundledBrandingResource("mipmap-anydpi/$LAUNCHER_RESOURCE_NAME_PREFIX$iconStyle.xml")
            ?: throw PatchException("Could not find the bundled icon style: $iconStyle")

    var replacedIcons = 0

    iconReferences.forEach { (resourceType, resourceName) ->
        resourceDirectories(resourceType).forEach { typeDirectory ->
            // Only the adaptive icon is replaced. A raster icon of the same name is used by
            // Android 7 and older, which the target apps no longer support.
            val iconFile = typeDirectory.resolve("$resourceName.xml")
            if (!iconFile.exists()) return@forEach

            iconFile.writeBytes(bundledIcon)
            replacedIcons++
        }
    }

    if (replacedIcons == 0) {
        throw PatchException(
            "Could not find an adaptive launcher icon to replace: " +
                    iconReferences.joinToString { (type, name) -> "@$type/$name" }
        )
    }
}
