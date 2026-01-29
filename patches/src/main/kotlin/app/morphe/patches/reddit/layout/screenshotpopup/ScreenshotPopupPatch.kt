package app.morphe.patches.reddit.layout.screenshotpopup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/reddit/patches/ScreenshotPopupPatch;"

@Suppress("unused")
val screenshotPopupPatch = bytecodePatch(
    name = "Disable screenshot popup",
    description = "Adds an option to disable the popup that appears when taking a screenshot."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        fun indexOfShowBannerInstruction(method: Method) =
            method.indexOfFirstInstruction {
                val reference = getReference<FieldReference>()
                opcode == Opcode.IGET_OBJECT &&
                        reference?.name?.contains("shouldShowBanner") == true &&
                        reference.definingClass.startsWith("Lcom/reddit/sharing/screenshot/") == true
            }

        fun indexOfSetValueInstruction(method: Method) =
            method.indexOfFirstInstruction {
                getReference<MethodReference>()?.name == "setValue"
            }

        fun indexOfBooleanInstruction(method: Method, startIndex: Int = 0) =
            method.indexOfFirstInstruction(startIndex) {
                val reference = getReference<FieldReference>()
                opcode == Opcode.SGET_OBJECT &&
                        reference?.definingClass == "Ljava/lang/Boolean;" &&
                        reference.type == "Ljava/lang/Boolean;"
            }

        val isScreenShotMethod: Method.() -> Boolean = {
            definingClass.startsWith("Lcom/reddit/sharing/screenshot/") &&
                    name == "invokeSuspend" &&
                    indexOfShowBannerInstruction(this) >= 0 &&
                    indexOfBooleanInstruction(this) >= 0 &&
                    indexOfSetValueInstruction(this) >= 0
        }

        var hookCount = 0

        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                if (method.isScreenShotMethod()) {
                    mutableClassDefBy(classDef)
                        .findMutableMethodOf(method)
                        .apply {
                            val showBannerIndex = indexOfShowBannerInstruction(this)
                            val booleanIndex = indexOfBooleanInstruction(this, showBannerIndex)
                            val booleanRegister = getInstruction<OneRegisterInstruction>(booleanIndex).registerA

                            addInstructions(
                                booleanIndex + 1,
                                """
                                    invoke-static { v$booleanRegister }, $EXTENSION_CLASS_DESCRIPTOR->disableScreenshotPopup(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                                    move-result-object v$booleanRegister
                                """
                            )
                            hookCount++
                        }
                }
            }
        }

        if (hookCount == 0) {
            throw PatchException("Failed to find hook method")
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
