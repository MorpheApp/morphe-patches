package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch;"

private const val EXTENSION_HEADER_ITEM_INTERFACE =
    "Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch\$HeaderItemInterface;"

@Suppress("unused")
val hideSidebarComponentsPatch = bytecodePatch(
    name = "Hide sidebar components",
    description = "Adds options to hide the sidebar components."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommunityDrawerBuilderFingerprint.match(
            CommunityDrawerBuilderParentFingerprint.originalClassDef
        ).method.addInstructions(
            0,
            """
                invoke-static/range { p2 .. p3 }, $EXTENSION_CLASS_DESCRIPTOR->hideComponents(Ljava/util/Collection;$EXTENSION_HEADER_ITEM_INTERFACE)Ljava/util/Collection;
                move-result-object p2
            """
        )

        HeaderItemUiModelToStringFingerprint.let {
            it.classDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_HEADER_ITEM_INTERFACE)
                // Add methods to access obfuscated header item fields.
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getItemName",
                        listOf(),
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        val headerItemField = fields.single { field ->
                            field.type == "Lcom/reddit/screens/drawer/community/HeaderItem;"
                        }

                        addInstructionsWithLabels(
                            0,
                            """
                                iget-object v0, p0, $headerItemField
                                if-nez v0, :get_name
                                const-string v0, ""
                                return-object v0
                                :get_name
                                invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
                                move-result-object v0
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}