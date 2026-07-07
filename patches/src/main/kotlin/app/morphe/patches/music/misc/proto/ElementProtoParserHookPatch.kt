/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.misc.proto

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.proto.createElementProtoParserHookPatch

val elementProtoParserHookPatch = createElementProtoParserHookPatch(sharedExtensionPatch)
