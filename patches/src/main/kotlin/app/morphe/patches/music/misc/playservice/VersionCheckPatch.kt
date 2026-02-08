@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.music.misc.playservice

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.metadata.isGreaterThan
import app.morphe.patches.shared.misc.metadata.packageMetaDataPatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

var is_7_16_or_greater: Boolean by Delegates.notNull()
    private set
var is_7_33_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_05_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_10_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_11_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_15_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_40_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_41_or_greater: Boolean by Delegates.notNull()
    private set

val versionCheckPatch = resourcePatch(
    description = "Find the major/minor version of the YouTube Music target app.",
) {
    dependsOn(packageMetaDataPatch)

    execute {
        is_7_16_or_greater = isGreaterThan(716)
        is_7_33_or_greater = isGreaterThan(733)
        is_8_05_or_greater = isGreaterThan(805)
        is_8_10_or_greater = isGreaterThan(810)
        is_8_11_or_greater = isGreaterThan(811)
        is_8_15_or_greater = isGreaterThan(815)
        is_8_40_or_greater = isGreaterThan(840)
        is_8_41_or_greater = isGreaterThan(841)
    }
}
