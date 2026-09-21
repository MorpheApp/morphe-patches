/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.guest

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object WelcomeViewModelConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/auth/login/screen/welcomev2/WelcomeV2ViewModel;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            smali = "Lcom/reddit/auth/login/screen/welcomev2/WelcomeV2ViewModel;->c0:Lb3o0;"
        )
    )
)

internal object StartupCredentialPickerFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/auth/login/screen/welcomev2/" +
        "WelcomeV2ViewModel\$viewState\$2\$1\$1;",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;")
)
