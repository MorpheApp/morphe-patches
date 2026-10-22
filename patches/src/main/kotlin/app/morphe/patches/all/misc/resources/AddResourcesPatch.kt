/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patches.all.misc.resources

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.util.resource.StringResourceSanitizer.sanitizeAndroidResourceString
import app.morphe.patches.util.resource.forEachRawResourceElement
import app.morphe.util.inputStreamFromBundledResource
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.logging.Logger

internal val localesYouTube = listOf(
    AppLocale("", ""), // Default English locale. Must be first.
    AppLocale("af-rZA", "af"),
    AppLocale("am-rET", "am"),
    AppLocale("ar-rSA", "ar"),
    AppLocale("as-rIN", "as"),
    AppLocale("az-rAZ", "az"),
    AppLocale("be-rBY", "be"),
    AppLocale("bg-rBG", "bg"),
    AppLocale("bn-rBD", "bn"),
    AppLocale("bs-rBA", "bs"),
    AppLocale("ca-rES", "ca"),
    AppLocale("cs-rCZ", "cs"),
    AppLocale("da-rDK", "da"),
    AppLocale("de-rDE", "de"),
    AppLocale("el-rGR", "el"),
    AppLocale("es-rES", "es"),
    AppLocale("et-rEE", "et"),
    AppLocale("eu-rES", "eu"),
    AppLocale("fa-rIR", "fa"),
    AppLocale("fi-rFI", "fi"),
    AppLocale("fil-rPH", "tl"),
    AppLocale("fr-rFR", "fr"),
    AppLocale("gl-rES", "gl"),
    AppLocale("gu-rIN", "gu"),
    AppLocale("hi-rIN", "hi"),
    AppLocale("hr-rHR", "hr"),
    AppLocale("hu-rHU", "hu"),
    AppLocale("hy-rAM", "hy"),
    AppLocale("in-rID", "in"),
    AppLocale("is-rIS", "is"),
    AppLocale("it-rIT", "it"),
    AppLocale("iw-rIL", "iw"),
    AppLocale("ja-rJP", "ja"),
    AppLocale("ka-rGE", "ka"),
    AppLocale("kk-rKZ", "kk"),
    AppLocale("km-rKH", "km"),
    AppLocale("kn-rIN", "kn"),
    AppLocale("ko-rKR", "ko"),
    AppLocale("ky-rKG", "ky"),
    AppLocale("lo-rLA", "lo"),
    AppLocale("lt-rLT", "lt"),
    AppLocale("lv-rLV", "lv"),
    AppLocale("mk-rMK", "mk"),
    AppLocale("ml-rIN", "ml"),
    AppLocale("mn-rMN", "mn"),
    AppLocale("mr-rIN", "mr"),
    AppLocale("ms-rMY", "ms"),
    AppLocale("my-rMM", "my"),
    AppLocale("nb-rNO", "nb"),
    AppLocale("ne-rNP", "ne"),
    AppLocale("nl-rNL", "nl"),
    AppLocale("or-rIN", "or"),
    AppLocale("pa-rIN", "pa"),
    AppLocale("pl-rPL", "pl"),
    AppLocale("pt-rBR", "pt-rBR"),
    AppLocale("pt-rPT", "pt-rPT"),
    AppLocale("ro-rRO", "ro"),
    AppLocale("ru-rRU", "ru"),
    AppLocale("si-rLK", "si"),
    AppLocale("sk-rSK", "sk"),
    AppLocale("sl-rSI", "sl"),
    AppLocale("sq-rAL", "sq"),
    AppLocale("sr-rCS", "b+sr+Latn"),
    AppLocale("sr-rSP", "sr"),
    AppLocale("sv-rSE", "sv"),
    AppLocale("sw-rKE", "sw"),
    AppLocale("ta-rIN", "ta"),
    AppLocale("te-rIN", "te"),
    AppLocale("th-rTH", "th"),
    AppLocale("tr-rTR", "tr"),
    AppLocale("uk-rUA", "uk"),
    AppLocale("ur-rIN", "ur"),
    AppLocale("uz-rUZ", "uz"),
    AppLocale("vi-rVN", "vi"),
    AppLocale("zh-rCN", "zh-rCN"),
    AppLocale("zh-rTW", "zh-rTW"),
    AppLocale("zu-rZA", "zu"),
    // Languages not found in YouTube.
    AppLocale("ga-rIE", "ga", false),
    AppLocale("kmr-rTR", "kmr", false),
    AppLocale("ckb-rIR", "ckb", false)
)

internal val localesReddit = listOf(
    AppLocale("", ""), // Default English locale. Must be first.
    AppLocale("ar-rSA", "ar"),
    AppLocale("de-rDE", "de"),
    AppLocale("es-rES", "es"),
    AppLocale("fi-rFI", "fi"),
    AppLocale("fr-rFR", "fr"),
    AppLocale("hi-rIN", "hi"),
    AppLocale("hu-rHU", "hu"),
    AppLocale("it-rIT", "it"),
    AppLocale("ja-rJP", "ja"),
    AppLocale("ko-rKR", "ko"),
    AppLocale("ms-rMY", "ms"),
    AppLocale("nl-rNL", "nl"),
    AppLocale("pl-rPL", "pl"),
    AppLocale("pt-rBR", "pt-rBR"),
    AppLocale("pt-rPT", "pt-rPT"),
    AppLocale("ru-rRU", "ru"),
    AppLocale("th-rTH", "th"),
    AppLocale("tr-rTR", "tr"),
    AppLocale("uk-rUA", "uk"),
    AppLocale("vi-rVN", "vi"),
    AppLocale("zh-rCN", "zh-rCN"),
    AppLocale("zh-rTW", "zh-rTW"),

    // Languages not found in Reddit.
    AppLocale("af-rZA", "af", false),
    AppLocale("am-rET", "am", false),
    AppLocale("as-rIN", "as", false),
    AppLocale("az-rAZ", "az", false),
    AppLocale("be-rBY", "be", false),
    AppLocale("bg-rBG", "bg", false),
    AppLocale("bn-rBD", "bn", false),
    AppLocale("bs-rBA", "bs", false),
    AppLocale("ca-rES", "ca", false),
    AppLocale("cs-rCZ", "cs", false),
    AppLocale("da-rDK", "da", false),
    AppLocale("el-rGR", "el", false),
    AppLocale("et-rEE", "et", false),
    AppLocale("eu-rES", "eu", false),
    AppLocale("fa-rIR", "fa", false),
    AppLocale("fil-rPH", "tl", false),
    AppLocale("gl-rES", "gl", false),
    AppLocale("gu-rIN", "gu", false),
    AppLocale("hr-rHR", "hr", false),
    AppLocale("hy-rAM", "hy", false),
    AppLocale("in-rID", "in", false),
    AppLocale("is-rIS", "is", false),
    AppLocale("iw-rIL", "iw", false),
    AppLocale("ka-rGE", "ka", false),
    AppLocale("kk-rKZ", "kk", false),
    AppLocale("km-rKH", "km", false),
    AppLocale("kn-rIN", "kn", false),
    AppLocale("ky-rKG", "ky", false),
    AppLocale("lo-rLA", "lo", false),
    AppLocale("lt-rLT", "lt", false),
    AppLocale("lv-rLV", "lv", false),
    AppLocale("mk-rMK", "mk", false),
    AppLocale("ml-rIN", "ml", false),
    AppLocale("mn-rMN", "mn", false),
    AppLocale("mr-rIN", "mr", false),
    AppLocale("my-rMM", "my", false),
    AppLocale("nb-rNO", "nb", false),
    AppLocale("ne-rNP", "ne", false),
    AppLocale("or-rIN", "or", false),
    AppLocale("pa-rIN", "pa", false),
    AppLocale("ro-rRO", "ro", false),
    AppLocale("sk-rSK", "sk", false),
    AppLocale("si-rLK", "si", false),
    AppLocale("sl-rSI", "sl", false),
    AppLocale("sq-rAL", "sq", false),
    AppLocale("sr-rCS", "b+sr+Latn", false),
    AppLocale("sr-rSP", "sr", false),
    AppLocale("sv-rSE", "sv", false),
    AppLocale("sw-rKE", "sw", false),
    AppLocale("ta-rIN", "ta", false),
    AppLocale("te-rIN", "te", false),
    AppLocale("ur-rIN", "ur", false),
    AppLocale("uz-rUZ", "uz", false),
    AppLocale("zu-rZA", "zu", false),
    AppLocale("ga-rIE", "ga", false),
    AppLocale("kmr-rTR", "kmr", false),
    AppLocale("ckb-rIR", "ckb", false)
)

internal val localesAll by lazy { (localesYouTube + localesReddit).distinct() }

internal class AppLocale(
    private val srcLocale: String,
    private val destLocale: String,
    val isBuiltInLanguage: Boolean = true
) {
    fun isDefaultLocale() = srcLocale.isEmpty()

    fun getSrcLocaleFolderName() = getValuesFolderName(srcLocale)
    fun getDestLocaleFolderName() = getValuesFolderName(destLocale)

    override fun toString(): String {
        return "AppLocale(srcLocale='${getSrcLocaleFolderName()}', destLocale='${getDestLocaleFolderName()}', " +
                "isBuiltInLanguage=$isBuiltInLanguage)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppLocale

        if (srcLocale != other.srcLocale) return false
        if (destLocale != other.destLocale) return false

        return true
    }

    override fun hashCode(): Int {
        var result = srcLocale.hashCode()
        result = 31 * result + destLocale.hashCode()
        return result
    }

    companion object {
        private fun getValuesFolderName(localeName: String): String {
            val folderName = "values"

            return if (localeName.isEmpty()) {
                folderName
            } else {
                "$folderName-$localeName"
            }
        }
    }
}

private enum class BundledResourceType {
    // Add more resource XML files as needed.
    ARRAYS,
    COLORS,
    STRINGS;

    override fun toString(): String {
        return super.toString().lowercase(Locale.US)
    }
}

private val appsToInclude = mutableSetOf<String>()

internal lateinit var locales : List<AppLocale>

internal fun setAddResourceLocale(appLocale: List<AppLocale>) {
    locales = appLocale
}

/**
 * Add all resources for the given app.
 */
internal fun addAppResources(appId: String) {
    appsToInclude.add(appId)
}

/**
 * A destination resource file that is edited as raw text. Multiple apps add resources to the
 * same file, and appending the text of all of them writes each file only once.
 *
 * A file no other patch decoded is not decoded here either. It is written with only the added
 * resources, which the patcher adds to the resources of the app, replacing the ones of the same
 * name. Decoding every strings file of the app only to append to it is most of the time of this patch.
 */
private class DestinationFile(
    val file: File,
    val text: String
) {
    val added = mutableListOf<Pair<Pair<String, String>, String>>()
}

private const val ADD_RESOURCES_THREAD_COUNT = 4

private const val RESOURCES_CLOSING_TAG = "</resources>"

private const val EMPTY_RESOURCES_FILE = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>"

internal val addResourcesPatch = resourcePatch(
    description = "Add resources such as strings or arrays to the app."
) {

    val defaultResourcesAdded = mutableSetOf<String>()

    finalize {
        val logger = Logger.getLogger(AppLocale.Companion::class.java.name)

        // Default resources added by each app and by the apps before it.
        val appDefaultResources = mutableMapOf<String, Set<String>>()

        // Strings files of the app, listed without decoding them.
        val appStringsFiles = listDecodableFiles("res/values*/strings.xml").toHashSet()

        fun openDestinationFile(
            destinationFiles: MutableMap<String, DestinationFile>,
            destSubPath: String
        ): DestinationFile {
            destinationFiles[destSubPath]?.let { return it }

            // Not decoded, unless another patch already did.
            val destFile = this@finalize.get(destSubPath, copy = false)
            val text = if (destFile.exists()) {
                destFile.readText()
            } else {
                destFile.parentFile?.mkdirs()
                EMPTY_RESOURCES_FILE
            }

            return DestinationFile(destFile, text).also {
                destinationFiles[destSubPath] = it
            }
        }

        fun writeDestinationFile(destSubPath: String, destination: DestinationFile) {
            if (destination.added.isEmpty()) return

            val text = destination.text
            val closingIndex = text.lastIndexOf(RESOURCES_CLOSING_TAG)
            if (closingIndex < 0) throw IllegalStateException("$destSubPath has no closing resources tag")

            // Keep only the last added resource of each name, as resources of the next apps
            // replace earlier ones.
            val latest = LinkedHashMap<Pair<String, String>, String>()
            destination.added.forEach { (key, element) ->
                latest.remove(key)
                latest[key] = element
            }

            val builder = StringBuilder(text.substring(0, closingIndex).trimEnd()).append('\n')
            latest.values.forEach { builder.append("    ").append(it).append('\n') }
            builder.append(text.substring(closingIndex))

            destination.file.writeText(builder.toString())
        }

        fun addResourcesFromFile(
            destinationFiles: MutableMap<String, DestinationFile>,
            appId: String,
            locale: AppLocale,
            resourceType: BundledResourceType
        ) {
            val isDefaultLocale = locale.isDefaultLocale()
            val srcFolderName = locale.getSrcLocaleFolderName()
            val srcSubPath = "$srcFolderName/$appId/$resourceType.xml"
            val destSubPath = "res/${locale.getDestLocaleFolderName()}/$resourceType.xml"

            val srcStream = inputStreamFromBundledResource(
                "addresources", srcSubPath
            )

            if (srcStream == null) {
                // String files are expected but other resource types are optional.
                if (resourceType == BundledResourceType.STRINGS) {
                    throw IllegalArgumentException("Could not find: $srcSubPath")
                }
                return
            }

            val srcText = srcStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val destination = openDestinationFile(destinationFiles, destSubPath)
            val defaultResources = appDefaultResources[appId]
            val localeStringsAdded = mutableSetOf<String>()

            forEachRawResourceElement(srcText) { rawElement ->
                val tagName = rawElement.tag
                val resourceName = rawElement.name
                val openTag = rawElement.openTag
                val inner = rawElement.inner
                var element = inner?.let { "$openTag$it</$tagName>" } ?: openTag

                if (resourceType == BundledResourceType.STRINGS && inner != null) {
                    // Check for bad text strings that will fail resource compilation.
                    val sanitized = sanitizeAndroidResourceString(
                        resourceName, inner, destSubPath
                    )
                    if (inner != sanitized) {
                        element = "$openTag$sanitized</$tagName>"
                    }
                }

                if (!localeStringsAdded.add(resourceName)) {
                    logger.warning(
                        "Duplicate string resource is declared: $srcFolderName " +
                                "resource: $resourceName"
                    )
                    return@forEachRawResourceElement
                }

                if (isDefaultLocale) {
                    // Duplicate check already handled above.
                    defaultResourcesAdded.add(resourceName)
                } else if (defaultResources?.contains(resourceName) != true) {
                    logger.fine {
                        "Ignoring removed default resource for locale " +
                                "(Issue will be fixed after next Crowdin sync): " +
                                "$srcFolderName resource: $resourceName"
                    }
                    return@forEachRawResourceElement
                }

                destination.added += (tagName to resourceName) to element
            }
        }

        fun addLocaleResources(locale: AppLocale) {
            val stringsSubPath = "res/${locale.getDestLocaleFolderName()}/strings.xml"
            if (locale.isBuiltInLanguage && stringsSubPath !in appStringsFiles) {
                // Either the user provided a bad APKM that doesn't have all languages,
                // or something changed and YouTube removed a language from the universal APK releases.
                logger.warning {
                    "!!! Provided app does not contain all region localizations. " +
                            "Locale: $locale does not exist in provided app file: $stringsSubPath"
                }
            }

            // Destination files of the locale. Multiple apps add resources to the same files.
            val destinationFiles = mutableMapOf<String, DestinationFile>()

            appsToInclude.forEach { app ->
                BundledResourceType.entries.forEach { type ->
                    addResourcesFromFile(destinationFiles, app, locale, type)
                }

                if (locale.isDefaultLocale()) {
                    appDefaultResources[app] = defaultResourcesAdded.toSet()
                }
            }

            destinationFiles.forEach { (destSubPath, destination) ->
                writeDestinationFile(destSubPath, destination)
            }
        }

        // The default locale must be added first since other locales are filtered by it.
        // Each locale only touches its own destination files, so the rest can run in parallel.
        val (defaultLocales, otherLocales) = locales.partition { it.isDefaultLocale() }
        defaultLocales.forEach { addLocaleResources(it) }

        val executor = Executors.newFixedThreadPool(ADD_RESOURCES_THREAD_COUNT)
        try {
            otherLocales.map { locale ->
                executor.submit { addLocaleResources(locale) }
            }.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
    }
}
