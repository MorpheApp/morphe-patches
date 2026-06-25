/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.proxy

import app.morphe.patcher.Fingerprint

internal const val CRONET_BUILDER_CLASS = "Lorg/chromium/net/CronetEngine\$Builder;"

private const val PROXY_CLASS = "Lorg/chromium/net/Proxy;"
private const val PROXY_HTTP_CONNECT_CALLBACK_CLASS = "Lorg/chromium/net/Proxy\$HttpConnectCallback;"
private const val PROXY_OPTIONS_CLASS = "Lorg/chromium/net/ProxyOptions;"

internal object BuildExperimentalFingerprint : Fingerprint(
    definingClass = CRONET_BUILDER_CLASS,
    name = "buildExperimental",
    returnType = "Lorg/chromium/net/ExperimentalCronetEngine;",
    parameters = emptyList()
)

internal object SetProxyOptionsFingerprint : Fingerprint(
    definingClass = CRONET_BUILDER_CLASS,
    name = "setProxyOptions",
    returnType = CRONET_BUILDER_CLASS,
    parameters = listOf(PROXY_OPTIONS_CLASS)
)

internal object CreateHttpProxyFingerprint : Fingerprint(
    definingClass = PROXY_CLASS,
    name = "createHttpProxy",
    returnType = PROXY_CLASS,
    parameters = listOf(
        "I",
        "Ljava/lang/String;",
        "I",
        "Ljava/util/concurrent/Executor;",
        PROXY_HTTP_CONNECT_CALLBACK_CLASS
    )
)

internal object FromProxyListFingerprint : Fingerprint(
    definingClass = PROXY_OPTIONS_CLASS,
    name = "fromProxyList",
    returnType = PROXY_OPTIONS_CLASS,
    parameters = listOf("Ljava/util/List;")
)

internal object FromProxyListLegacyFingerprint : Fingerprint(
    definingClass = PROXY_OPTIONS_CLASS,
    name = "fromProxyList",
    returnType = PROXY_OPTIONS_CLASS,
    parameters = listOf(
        "Ljava/util/List;",
        "I"
    )
)

internal object ExtensionProxyPatchUsProxyListIntFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "useProxyListInt",
    returnType = "Z",
    parameters = listOf()
)
