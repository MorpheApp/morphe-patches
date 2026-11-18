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

import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.readXml
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.xml.ResourceXml
import app.morphe.patches.util.resource.StringResourceSanitizer.sanitizeAndroidResourceString
import app.morphe.util.inputStreamFromBundledResource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.logging.Logger
import javax.xml.parsers.SAXParserFactory

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
 * The translated strings the build converted to JSON with [TranslationsJson], a file for each locale and app.
 */
private const val TRANSLATIONS_BUNDLED_DIR = "addresources-json"

/**
 * Where the translated strings of each locale go in the patched app, read by the extension.
 */
private const val TRANSLATIONS_ASSETS_DIR = "assets/morphe/strings"

/**
 * String resource with the name of the translations file of each locale, read by the extension.
 */
private const val TRANSLATIONS_FILE_STRING = "morphe_translations_file"

internal val addResourcesPatch = patch(
    description = "Add resources such as strings or arrays to the app."
) {

    finalize {
        val logger = Logger.getLogger(AppLocale.Companion::class.java.name)

        // Strings files of the app, listed without decoding them.
        val appStringsFiles = listDecodableFiles("res/values*/strings.xml").toHashSet()

        /**
         * Adds the resources of a bundled resource file of the default locale to the app.
         *
         * A destination file no other patch decoded is not decoded here either. It is opened only to add
         * resources, which the patcher adds to the resources of the app, replacing the ones of the same
         * name. The last resource added of each name is kept, as resources of the next apps replace
         * earlier ones.
         */
        fun addResourcesFromFile(
            destinationFiles: MutableMap<String, ResourceXml>,
            appId: String,
            locale: AppLocale,
            resourceType: BundledResourceType
        ) {
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

            val resourcesAdded = mutableSetOf<String>()

            srcStream.use { readXml(it) }.use { source ->
                val destination = destinationFiles.getOrPut(destSubPath) {
                    resourceXml(destSubPath, copy = false)
                }

                source.root.children.forEach { element ->
                    val resourceName = element.attribute("name") ?: return@forEach

                    // Check for bad text strings that will fail resource compilation.
                    // A string with markup is left as it is.
                    val sanitized = if (resourceType == BundledResourceType.STRINGS && element.children.isEmpty()) {
                        sanitizeAndroidResourceString(resourceName, element.text, destSubPath)
                    } else {
                        null
                    }

                    if (!resourcesAdded.add(resourceName)) {
                        logger.warning(
                            "Duplicate string resource is declared: $srcFolderName " +
                                    "resource: $resourceName"
                        )
                        return@forEach
                    }

                    val copy = destination.root.appendCopy(element)
                    if (sanitized != null && sanitized != element.text) copy.text = sanitized
                }
            }
        }

        /**
         * Names the translations file of the locale in a string resource of its values folder.
         * The extension reads it to find the file, so Android matches the language of the app to a file
         * as it would to translated string resources, including the old language codes of the folders
         * and its fallbacks between regions and scripts.
         */
        fun addTranslationsFileName(locale: AppLocale, fileName: String) {
            resourceXml("res/${locale.getDestLocaleFolderName()}/strings.xml", copy = false).use { strings ->
                strings.root.appendChild("string") {
                    setAttribute("name", TRANSLATIONS_FILE_STRING)
                    text = fileName
                }
            }
        }

        fun addDefaultResources(locale: AppLocale) {
            // Multiple apps add resources to the same files.
            val destinationFiles = mutableMapOf<String, ResourceXml>()

            try {
                appsToInclude.forEach { app ->
                    BundledResourceType.entries.forEach { type ->
                        addResourcesFromFile(destinationFiles, app, locale, type)
                    }
                }
            } finally {
                destinationFiles.values.forEach { it.close() }
            }

            // The default strings are resources and have no file.
            addTranslationsFileName(locale, "")
        }

        // Only needed to convert strings files at patch time.
        val defaultStringNames by lazy {
            appsToInclude.flatMapTo(HashSet()) { app ->
                inputStreamFromBundledResource("addresources", "values/$app/strings.xml")
                    ?.use { TranslationsJson.readStrings(it) }.orEmpty().map { it.first }
            }
        }

        /**
         * Writes the translated strings of all apps for the locale as one JSON object, which the extension
         * reads in place of string resources. The build converted the strings of each app to a JSON object
         * of its own, and they are joined here without parsing them. Patches built without that conversion
         * are converted here instead, which is slower. Of two strings of the same name, the
         * extension keeps the one of the later app.
         */
        fun addTranslations(locale: AppLocale) {
            val stringsSubPath = "res/${locale.getDestLocaleFolderName()}/strings.xml"
            if (locale.isBuiltInLanguage && stringsSubPath !in appStringsFiles) {
                // Either the user provided a bad APKM that doesn't have all languages,
                // or something changed and YouTube removed a language from the universal APK releases.
                logger.warning {
                    "!!! Provided app does not contain all region localizations. " +
                            "Locale: $locale does not exist in provided app file: $stringsSubPath"
                }
            }

            val joined = ByteArrayOutputStream()
            joined.write('{'.code)
            appsToInclude.forEach { app ->
                val srcFolderName = locale.getSrcLocaleFolderName()
                val json = inputStreamFromBundledResource(
                    TRANSLATIONS_BUNDLED_DIR, "$srcFolderName/$app.json"
                )?.use { it.readBytes() }
                    // Patches built without the conversion bundle the strings files instead.
                    ?: inputStreamFromBundledResource("addresources", "$srcFolderName/$app/strings.xml")?.use {
                        TranslationsJson.convert(it, defaultStringNames)
                    }
                    ?: return@forEach

                // Each file is a JSON object with nothing around its braces.
                if (json.size <= 2) return@forEach
                if (joined.size() > 1) joined.write(','.code)
                joined.write(json, 1, json.size - 2)
            }
            joined.write('}'.code)

            val name = locale.getDestLocaleFolderName().removePrefix("values-")
            resourceFile("$TRANSLATIONS_ASSETS_DIR/$name.json", copy = false).apply {
                parentFile.mkdirs()
                writeBytes(joined.toByteArray())
            }
            addTranslationsFileName(locale, name)
        }

        locales.forEach { locale ->
            if (locale.isDefaultLocale()) addDefaultResources(locale) else addTranslations(locale)
        }
    }
}

/**
 * Converts translated strings files to the JSON objects that [addResourcesPatch] adds to the app.
 *
 * The build runs [main] to convert the strings files of addresources, so patching only copies them.
 * Patches built without it are converted while patching.
 */
internal object TranslationsJson {

    /**
     * Converts the strings files of addresources to a JSON file for each translated locale and app.
     *
     * @param args The addresources folder, and the folder to write [TRANSLATIONS_BUNDLED_DIR] to.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val source = File(args[0])
        val output = File(args[1]).resolve(TRANSLATIONS_BUNDLED_DIR)

        val defaultNames = source.resolve("values").listFiles()!!.flatMapTo(HashSet()) { app ->
            app.resolve("strings.xml").takeIf { it.isFile }
                ?.inputStream()?.use { readStrings(it) }.orEmpty().map { it.first }
        }

        output.deleteRecursively()
        source.listFiles()!!.filter { it.name.startsWith("values-") }.forEach { locale ->
            locale.listFiles()!!.forEach { app ->
                val file = app.resolve("strings.xml")
                if (!file.isFile) return@forEach

                val target = output.resolve("${locale.name}/${app.name}.json")
                target.parentFile.mkdirs()
                target.writeBytes(file.inputStream().use { convert(it, defaultNames) })
            }
        }
    }

    /**
     * @param defaultNames Names of the strings of the default locale. Other strings are left out, as a string
     *                     removed from the default locale stays translated until the next Crowdin sync.
     * @return The strings of the strings file as a JSON object, with nothing around its braces.
     */
    fun convert(strings: InputStream, defaultNames: Set<String>): ByteArray {
        val seen = HashSet<String>()
        return readStrings(strings)
            .filter { (name, _) -> name in defaultNames && seen.add(name) }
            .joinToString(",", "{", "}") { (name, text) -> json(name) + ":" + json(resolve(text)) }
            .toByteArray()
    }

    /**
     * @return The names and texts of the strings of a strings file. The text of a string with markup
     *         is its text without the markup.
     */
    fun readStrings(strings: InputStream): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val handler = object : DefaultHandler() {
            private var depth = 0
            private var name: String? = null
            private val text = StringBuilder()

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                depth++
                if (depth == 2 && qName.ifEmpty { localName } == "string") {
                    name = attributes.getValue("name")
                    text.setLength(0)
                }
            }

            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (name != null) text.append(chars, start, length)
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                if (depth == 2) {
                    name?.let { result += it to text.toString() }
                    name = null
                }
                depth--
            }
        }
        SAXParserFactory.newInstance().newSAXParser().parse(strings, handler)
        return result
    }

    /**
     * What aapt2 makes of the text of a string: quotes keep whitespace and are dropped,
     * escapes are resolved, and whitespace outside quotes is collapsed and trimmed.
     */
    private fun resolve(text: String): String {
        val result = StringBuilder()
        var quoted = false
        var pendingSpace = false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (!quoted && (char == ' ' || char == '\n' || char == '\t' || char == '\r')) {
                pendingSpace = result.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                result.append(' ')
                pendingSpace = false
            }
            when (char) {
                '"' -> quoted = !quoted
                '\\' -> if (index < text.length) {
                    when (val escaped = text[index++]) {
                        'n' -> result.append('\n')
                        't' -> result.append('\t')
                        'u' -> {
                            result.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> result.append(escaped)
                    }
                }
                else -> result.append(char)
            }
        }
        return result.toString()
    }

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
