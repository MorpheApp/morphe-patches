package app.morphe.patches.youtube.layout.hide.fullscreenambientmode

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.layout.hide.ambientmode.ambientModePatch

@Deprecated("Use 'Ambient mode' instead.")
val disableFullscreenAmbientModePatch = bytecodePatch{
    dependsOn(ambientModePatch)
}