@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.youtube.misc.playservice

import app.morphe.patcher.patch.bytecodePatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

@Deprecated("20.14.43 is the lowest supported version")
var is_19_17_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_18_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_23_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_25_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_26_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_29_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_32_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_33_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_34_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_35_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_36_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_41_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_43_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_46_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_47_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_49_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_02_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_03_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_05_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_06_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_07_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_09_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_10_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_14_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_15_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_19_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_20_or_greater : Boolean by Delegates.notNull()
    private set
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

val versionCheckPatch = bytecodePatch {
    execute {
        val versionName = packageMetadata.versionName
        fun isEqualsOrGreaterThan(version: String): Boolean {
            return versionName >= version
        }

        // All bug fix releases always seem to use the same play store version as the minor version.
        is_19_17_or_greater = isEqualsOrGreaterThan("19.17.00")
        is_19_18_or_greater = isEqualsOrGreaterThan("19.18.00")
        is_19_23_or_greater = isEqualsOrGreaterThan("19.23.00")
        is_19_25_or_greater = isEqualsOrGreaterThan("19.25.00")
        is_19_26_or_greater = isEqualsOrGreaterThan("19.26.00")
        is_19_29_or_greater = isEqualsOrGreaterThan("19.29.00")
        is_19_32_or_greater = isEqualsOrGreaterThan("19.32.00")
        is_19_33_or_greater = isEqualsOrGreaterThan("19.33.00")
        is_19_34_or_greater = isEqualsOrGreaterThan("19.34.00")
        is_19_35_or_greater = isEqualsOrGreaterThan("19.35.00")
        is_19_36_or_greater = isEqualsOrGreaterThan("19.36.00")
        is_19_41_or_greater = isEqualsOrGreaterThan("19.41.00")
        is_19_43_or_greater = isEqualsOrGreaterThan("19.43.00")
        is_19_46_or_greater = isEqualsOrGreaterThan("19.46.00")
        is_19_47_or_greater = isEqualsOrGreaterThan("19.47.00")
        is_19_49_or_greater = isEqualsOrGreaterThan("19.49.00")
        is_20_02_or_greater = isEqualsOrGreaterThan("20.02.00")
        is_20_03_or_greater = isEqualsOrGreaterThan("20.03.00")
        is_20_05_or_greater = isEqualsOrGreaterThan("20.05.00")
        is_20_06_or_greater = isEqualsOrGreaterThan("20.06.00")
        is_20_07_or_greater = isEqualsOrGreaterThan("20.07.00")
        is_20_09_or_greater = isEqualsOrGreaterThan("20.09.00")
        is_20_10_or_greater = isEqualsOrGreaterThan("20.10.00")
        is_20_14_or_greater = isEqualsOrGreaterThan("20.14.00")
        is_20_15_or_greater = isEqualsOrGreaterThan("20.15.00")
        is_20_19_or_greater = isEqualsOrGreaterThan("20.19.00")
        is_20_20_or_greater = isEqualsOrGreaterThan("20.20.00")
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
    }
}
