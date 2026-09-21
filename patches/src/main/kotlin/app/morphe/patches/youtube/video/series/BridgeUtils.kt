package app.morphe.patches.youtube.video.series

import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val OUR_PREFIX = "Lapp/morphe/extension/youtube/series/"

internal fun MethodReference.signature() =
    name + "(" + parameterTypes.joinToString("") + ")" + returnType
