package app.morphe.patches.reddit.utils.patch

internal enum class PatchList(
    val title: String,
    val summary: String,
    var included: Boolean? = false
) {
    CHANGE_PACKAGE_NAME(
        "Change package name",
        "Changes the package name for Reddit to the name specified in patch options."
    ),
    CUSTOM_BRANDING_NAME_FOR_REDDIT(
        "Custom branding name for Reddit",
        "Changes the Reddit app name to the name specified in patch options."
    ),
    DISABLE_SCREENSHOT_POPUP(
        "Disable screenshot popup",
        "Adds an option to disable the popup that appears when taking a screenshot."
    ),
    HIDE_ADS(
        "Hide ads",
        "Adds options to hide ads."
    ),
    HIDE_NAVIGATION_BUTTONS(
        "Hide navigation buttons",
        "Adds options to hide buttons in the navigation bar."
    ),
    HIDE_RECOMMENDED_COMMUNITIES_SHELF(
        "Hide recommended communities shelf",
        "Adds an option to hide the recommended communities shelves in subreddits."
    ),
    HIDE_SIDEBAR_COMPONENTS(
        "Hide sidebar components",
        "Adds options to hide the sidebar components."
    ),
    HIDE_TRENDING_TODAY_SHELF(
        "Hide Trending Today shelf",
        "Adds an option to hide the Trending Today shelf from search suggestions."
    ),
    OPEN_LINKS_DIRECTLY(
        "Open links directly",
        "Adds an option to skip over redirection URLs in external links."
    ),
    OPEN_LINKS_EXTERNALLY(
        "Open links externally",
        "Adds an option to always open links in your browser instead of in the in-app-browser."
    ),
    REMOVE_SUBREDDIT_DIALOG(
        "Remove subreddit dialog",
        "Adds options to remove the NSFW community warning and notifications suggestion dialogs by dismissing them automatically."
    ),
    SANITIZE_SHARING_LINKS(
        "Sanitize sharing links",
        "Adds an option to sanitize sharing links by removing tracking query parameters."
    ),
    SETTINGS_FOR_REDDIT(
        "Settings for Reddit",
        "Applies mandatory patches to implement RVX Morphed settings into the application."
    ),
}