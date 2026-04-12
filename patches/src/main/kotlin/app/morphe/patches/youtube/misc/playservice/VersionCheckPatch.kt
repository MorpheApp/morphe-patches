@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.youtube.misc.playservice

import app.morphe.patcher.patch.bytecodePatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

var is_20_21_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_22_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_26_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_28_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_29_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_30_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_31_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_34_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_37_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_38_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_39_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_40_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_41_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_43_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_45_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_46_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_49_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_02_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_03_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_05_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_06_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_07_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_08_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_10_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_11_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_12_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_14_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_15_or_greater : Boolean by Delegates.notNull()
    private set

val versionCheckPatch = bytecodePatch {
    execute {
        val versionName = packageMetadata.versionName
        fun isEqualsOrGreaterThan(version: String): Boolean {
            return versionName >= version
        }

        // All bug fix releases always seem to use the same play store version as the minor version.
        is_20_21_or_greater = isEqualsOrGreaterThan("20.21.00")
        is_20_22_or_greater = isEqualsOrGreaterThan("20.22.00")
        is_20_26_or_greater = isEqualsOrGreaterThan("20.26.00")
        is_20_28_or_greater = isEqualsOrGreaterThan("20.28.00")
        is_20_29_or_greater = isEqualsOrGreaterThan("20.29.00")
        is_20_30_or_greater = isEqualsOrGreaterThan("20.30.00")
        is_20_31_or_greater = isEqualsOrGreaterThan("20.31.00")
        is_20_34_or_greater = isEqualsOrGreaterThan("20.34.00")
        is_20_37_or_greater = isEqualsOrGreaterThan("20.37.00")
        is_20_38_or_greater = isEqualsOrGreaterThan("20.38.00")
        is_20_39_or_greater = isEqualsOrGreaterThan("20.39.00")
        is_20_40_or_greater = isEqualsOrGreaterThan("20.40.00")
        is_20_41_or_greater = isEqualsOrGreaterThan("20.41.00")
        is_20_43_or_greater = isEqualsOrGreaterThan("20.43.00")
        is_20_45_or_greater = isEqualsOrGreaterThan("20.45.00")
        is_20_46_or_greater = isEqualsOrGreaterThan("20.46.00")
        is_20_49_or_greater = isEqualsOrGreaterThan("20.49.00")
        is_21_02_or_greater = isEqualsOrGreaterThan("21.02.000")
        is_21_03_or_greater = isEqualsOrGreaterThan("21.03.000")
        is_21_05_or_greater = isEqualsOrGreaterThan("20.05.000")
        is_21_06_or_greater = isEqualsOrGreaterThan("21.06.000")
        is_21_07_or_greater = isEqualsOrGreaterThan("21.07.000")
        is_21_08_or_greater = isEqualsOrGreaterThan("21.08.000")
        is_21_10_or_greater = isEqualsOrGreaterThan("21.10.000")
        is_21_11_or_greater = isEqualsOrGreaterThan("21.11.000")
        is_21_12_or_greater = isEqualsOrGreaterThan("21.12.000")
        is_21_14_or_greater = isEqualsOrGreaterThan("21.14.000")
        is_21_15_or_greater = isEqualsOrGreaterThan("21.15.000")
    }
}
