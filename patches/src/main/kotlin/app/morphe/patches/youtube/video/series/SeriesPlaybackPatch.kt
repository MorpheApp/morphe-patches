package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.youtube.layout.playlistautoplay.NavigationIntentEnumFingerprint
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags

private class SeriesNavigationWrapperFingerprint(enumType: String) : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(enumType, "L", "L"),
)

private class SeriesNavigationDispatchFingerprint(wrapperType: String) : Fingerprint(
    returnType = "V",
    parameters = listOf(wrapperType),
    custom = { method, classDef ->
        method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            classDef.methods.any { sibling ->
                sibling.implementation != null && sibling.returnType == "I" &&
                    sibling.parameterTypes.singleOrNull() == wrapperType
            }
    },
)

/** Share the host navigation contract used by Morphe's Disable playlist autoplay patch. */
internal fun BytecodePatchContext.wireSeriesPlayback() {
    val enumType = NavigationIntentEnumFingerprint.originalClassDef.type
    val wrapper = SeriesNavigationWrapperFingerprint(enumType).matchAll(1..1).single().originalClassDef
    val field = wrapper.fields.singleOrNull { it.type == enumType && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: throw PatchException("Series Tracker: navigation intent field is missing or ambiguous")
    val matches = SeriesNavigationDispatchFingerprint(wrapper.type).matchAll()
    if (matches.isEmpty()) throw PatchException("Series Tracker: native playlist navigation missing")
    matches.forEach { match ->
        var method = match.method
        if (method.implementation!!.registerCount <= 2) method = method.cloneParameters()
        val register = method.findFreeRegister(0)
        method.addInstructionsWithLabels(0, """
            iget-object v$register, p1, $field
            invoke-static {v$register}, ${OUR_PREFIX}SeriesPlayback;->navigate(Ljava/lang/Enum;)Z
            move-result v$register
            if-eqz v$register, :series_native_navigation
            return-void
            :series_native_navigation
            nop
        """)
    }
}
