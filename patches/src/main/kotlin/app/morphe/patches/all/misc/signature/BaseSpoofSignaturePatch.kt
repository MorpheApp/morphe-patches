package app.morphe.patches.all.misc.signature

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

class AppInfo(
    val packageName: String? = "",
    val certificateData: String? = "",
)

fun baseSpoofSignaturePatch(appInfoSupplier: () -> AppInfo) = bytecodePatch {
    // Lazy, so that patch options above are initialized before they are accessed.
    val replacements by lazy {
        with(appInfoSupplier()) {
            buildMap {
                if (!packageName.isNullOrEmpty()) put(PACKAGE_NAME, "\"$packageName\"")
                if (!certificateData.isNullOrEmpty()) put(CERTIFICATE_BASE64, "\"$certificateData\"")
            }
        }
    }

    val logger = Logger.getLogger(this::class.java.name)

    extendWith("extensions/spoof-signature.mpe")

    execute {
        if (replacements.size != 2) {
            logger.warning("Invalid package name or certificate data, skipping patch")

            return@execute
        }

        spoofSignatureFingerprint.method.apply {
            replacements.forEach { (k, v) ->
                val index = indexOfFirstStringInstructionOrThrow(k)
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                replaceInstruction(index, "const-string v$register, $v")
            }

            applicationFingerprint.classDef.setSuperClass(definingClass)
        }
    }
}
