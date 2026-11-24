/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.playlistsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Creates the menu of the action bar of a page, such as the search item of a playlist page.
 * A playlist page has an action bar of its own instead of the toolbar of the app.
 * The first parameter is the menu. 20.26 and older have no context parameter at the end.
 */
internal object ActionBarCreateMenuFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        string("Unhandled menu item %s"),
        opcode(Opcode.RETURN_VOID)
    )
)
