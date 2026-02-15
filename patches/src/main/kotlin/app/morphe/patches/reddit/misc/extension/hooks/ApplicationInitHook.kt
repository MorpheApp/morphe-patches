package app.morphe.patches.reddit.misc.extension.hooks

import app.morphe.patches.shared.misc.extension.activityOnCreateExtensionHook

internal val redditActivityOnCreateHook = activityOnCreateExtensionHook(
    activityClassType = "Lcom/reddit/launch/main/MainActivity;",
    targetBundleMethod = true,
)

internal val redditApplicationOnCreateHook = activityOnCreateExtensionHook(
    activityClassType = "Lcom/reddit/frontpage/FrontpageApplication;",
    targetBundleMethod = false,
)
