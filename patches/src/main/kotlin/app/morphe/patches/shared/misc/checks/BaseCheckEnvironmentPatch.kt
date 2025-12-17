package app.morphe.patches.shared.misc.checks

import android.os.Build.BOARD
import android.os.Build.BOOTLOADER
import android.os.Build.BRAND
import android.os.Build.CPU_ABI
import android.os.Build.CPU_ABI2
import android.os.Build.DEVICE
import android.os.Build.DISPLAY
import android.os.Build.FINGERPRINT
import android.os.Build.HARDWARE
import android.os.Build.HOST
import android.os.Build.ID
import android.os.Build.MANUFACTURER
import android.os.Build.MODEL
import android.os.Build.PRODUCT
import android.os.Build.RADIO
import android.os.Build.TAGS
import android.os.Build.TYPE
import android.os.Build.USER
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableEncodedValue
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableLongEncodedValue
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.immutable.value.ImmutableLongEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/checks/CheckEnvironmentPatch;"

fun checkEnvironmentPatch(
    mainActivityOnCreateFingerprint: Fingerprint,
    extensionPatch: Patch<*>,
    vararg compatiblePackages: String,
) = bytecodePatch(
    description = "Checks, if the application was patched by, otherwise warns the user.",
) {
    compatibleWith(*compatiblePackages)

    dependsOn(
        extensionPatch,
    )

    execute {
        fun setPatchInfo() {
            fun <T : MutableEncodedValue> Fingerprint.setClassFields(vararg fieldNameValues: Pair<String, T>) {
                val fieldNameValueMap = mapOf(*fieldNameValues)

                classDef.fields.forEach { field ->
                    field.initialValue = fieldNameValueMap[field.name] ?: return@forEach
                }
            }

            PatchInfoFingerprint.setClassFields(
                "PATCH_TIME" to System.currentTimeMillis().encoded,
            )

            fun setBuildInfo() {
                PatchInfoBuildFingerprint.setClassFields(
                    "PATCH_BOARD" to BOARD.encodedAndHashed,
                    "PATCH_BOOTLOADER" to BOOTLOADER.encodedAndHashed,
                    "PATCH_BRAND" to BRAND.encodedAndHashed,
                    "PATCH_CPU_ABI" to CPU_ABI.encodedAndHashed,
                    "PATCH_CPU_ABI2" to CPU_ABI2.encodedAndHashed,
                    "PATCH_DEVICE" to DEVICE.encodedAndHashed,
                    "PATCH_DISPLAY" to DISPLAY.encodedAndHashed,
                    "PATCH_FINGERPRINT" to FINGERPRINT.encodedAndHashed,
                    "PATCH_HARDWARE" to HARDWARE.encodedAndHashed,
                    "PATCH_HOST" to HOST.encodedAndHashed,
                    "PATCH_ID" to ID.encodedAndHashed,
                    "PATCH_MANUFACTURER" to MANUFACTURER.encodedAndHashed,
                    "PATCH_MODEL" to MODEL.encodedAndHashed,
                    "PATCH_PRODUCT" to PRODUCT.encodedAndHashed,
                    "PATCH_RADIO" to RADIO.encodedAndHashed,
                    "PATCH_TAGS" to TAGS.encodedAndHashed,
                    "PATCH_TYPE" to TYPE.encodedAndHashed,
                    "PATCH_USER" to USER.encodedAndHashed,
                )
            }

            try {
                Class.forName("android.os.Build")
                // This only works on Android,
                // because it uses Android APIs.
                setBuildInfo()
            } catch (_: ClassNotFoundException) {
            }
        }

        fun invokeCheck() = MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->check(Landroid/app/Activity;)V",
        )

        setPatchInfo()
        invokeCheck()
    }
}

@OptIn(ExperimentalEncodingApi::class)
private val String.encodedAndHashed
    get() = MutableStringEncodedValue(
        ImmutableStringEncodedValue(
            Base64.encode(
                MessageDigest.getInstance("SHA-1")
                    .digest(this.toByteArray(StandardCharsets.UTF_8)),
            ),
        ),
    )

private val Long.encoded get() = MutableLongEncodedValue(ImmutableLongEncodedValue(this))
