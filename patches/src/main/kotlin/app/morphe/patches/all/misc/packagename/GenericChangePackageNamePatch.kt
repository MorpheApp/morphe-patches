/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-cli
 */

package app.morphe.patches.all.misc.packagename

@Suppress("unused")
val genericChangePackageNamePatch = baseChangePackageNamePatch(
    name = "Change package name",
    description = "Appends \".morphe\" to the package name by default. " +
            "Changing the package name of the app can lead to unexpected issues."
)
