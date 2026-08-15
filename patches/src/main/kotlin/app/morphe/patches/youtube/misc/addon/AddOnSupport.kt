/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.addon

/**
 * Hooks that add-on patch bundles attach to.
 *
 * Add-on bundles are loaded in their own class loader and cannot reference any patch of this
 * bundle, so the contract between the two is the patched app itself:
 *
 * - The patches that own a hook inject a call to the matching injection point of `AddOnApi`,
 *   which dispatches it to the listeners an add-on subscribes with. The injections are part of
 *   those patches, so an add-on works whenever the feature it attaches to is patched in, without
 *   the user having to enable a patch of its own.
 * - An add-on adds a call to its own static registration method to `AddOnManager.registerAddOns()`,
 *   which this bundle declares but never modifies. The add-ons are loaded on the first hook.
 * - An add-on declares its preferences in `morphe_addon_prefs.xml`, which the settings patch merges
 *   into the Morphe settings and then removes.
 */
internal const val EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/addon/AddOnApi;"

/**
 * Button slots an add-on can claim at runtime with `AddOnApi.createLegacyButton()`.
 * The slots are hidden until an add-on uses them.
 */
internal const val LEGACY_BUTTON_SLOTS_RESOURCE_DIRECTORY = "addonbuttons"
