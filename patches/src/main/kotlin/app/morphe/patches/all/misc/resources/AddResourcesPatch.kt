package app.morphe.patches.all.misc.resources

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.forEachChildElement
import app.morphe.util.getNode
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.resource.StringResource
import java.util.logging.Level
import java.util.logging.Logger

private class AppLocale(
    private val srcLocale: String,
    private val destLocale: String,
    val isBuiltInLanguage: Boolean = true
) {
    private fun getValuesFolderName(localeName: String): String {
        val folderName = "values"

        return if (localeName.isEmpty()) {
            folderName
        } else {
            "$folderName-$localeName"
        }
    }

    fun isDefaultLocale() = srcLocale.isEmpty()

    fun getSrcLocaleFolderName() = getValuesFolderName(srcLocale)
    fun getDestLocaleFolderName() = getValuesFolderName(destLocale)

    override fun toString(): String {
        return "AppLocale(srcLocale='$srcLocale', destLocale='$destLocale', isBuiltInLanguage=$isBuiltInLanguage)"
    }
}

private val locales = listOf(
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
    AppLocale("ga-rIE", "ga", isBuiltInLanguage = false)
)

private val appsToInclude = mutableSetOf<String>()

private val defaultResourcesAdded = mutableSetOf<String>()

internal val addResourcesPatch = resourcePatch(
    description = "Add resources such as strings or arrays to the app."
) {

    finalize {
        fun addResourcesFromFile(
            appId: String,
            locale: AppLocale,
            resourceType: String
        ) {
            val isDefaultLocale = locale.isDefaultLocale()
            val srcFolderName = locale.getSrcLocaleFolderName()
            val srcSubPath = "$srcFolderName/$appId/$resourceType.xml"
            val destSubPath = "res/${locale.getDestLocaleFolderName()}/$resourceType.xml"

            val srcStream = inputStreamFromBundledResource(
                "addresources", srcSubPath
            )

            if (srcStream == null) {
                // Localized arrays are optional, but string files are expected.
                if (resourceType == "string") {
                    throw IllegalArgumentException("Could not find: $srcSubPath")
                }
                return
            }

            srcStream.use {
                val destFile = this@finalize[destSubPath]
                if (!destFile.exists()) {
                    if (locale.isBuiltInLanguage) {
                        throw IllegalStateException(
                            "Expected to find locale: $locale but file does not exist in target app: $destFile"
                        )
                    }

                    destFile.parentFile?.mkdirs()
                    if (!destFile.createNewFile()) throw IllegalStateException()
                    destFile.writeText(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>"
                    )
                }

                document(destSubPath).use { destDoc ->
                    val destResourceNode = destDoc.getNode("resources")

                    document(srcStream).use { srcDoc ->
                        // Check for bad localized files with duplicate strings.
                        val localeStringsAdded = mutableSetOf<String>()

                        srcDoc.getElementsByTagName(
                            "resources"
                        ).item(0)?.forEachChildElement { srcNode ->
                            val resourceName = srcNode.getAttributeNode("name").value

                            fun getLogger(): Logger = Logger.getLogger(StringResource.javaClass.name)!!

                            if (!localeStringsAdded.add(resourceName)) {
                                getLogger().warning(
                                    "Duplicate string resource is declared: $srcFolderName " +
                                            "resource: $resourceName"
                                )
                                return@forEachChildElement
                            }

                            if (isDefaultLocale) {
                                // Duplicate check alreday handled above.
                                defaultResourcesAdded.add(resourceName)
                            } else if (!defaultResourcesAdded.contains(resourceName)) {
                                // TODO: Enable when patcher/CLI supports debug/dev logging.
                                if (false) getLogger().log(Level.INFO) {
                                    "Ignoring removed default resource for locale (Issue will be fixed after next Crowdin sync): " +
                                            "$srcFolderName resource: $resourceName"
                                }
                                return@forEachChildElement
                            }

                            val importedSrcNode = destDoc.importNode(srcNode, true)
                            destResourceNode.appendChild(importedSrcNode)
                        }
                    }
                }
            }
        }

        appsToInclude.forEach { app ->
            locales.forEach { locale ->
                addResourcesFromFile(app, locale, "strings")
                addResourcesFromFile(app, locale, "arrays")
            }
        }
    }
}

/**
 * Add all resources for the given app.
 */
internal fun addAppResources(appId: String) {
    appsToInclude.add(appId)
}
