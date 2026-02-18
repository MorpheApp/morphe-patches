/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.shared.spoof.js.nsigsolver.impl;

import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8ScriptExecutionException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.CacheError;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.ScriptUtils;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeProviderError;
import app.morphe.extension.shared.spoof.js.nsigsolver.runtime.JsRuntimeChalBaseJCP;
import app.morphe.extension.shared.spoof.js.nsigsolver.runtime.Script;
import app.morphe.extension.shared.spoof.js.nsigsolver.runtime.ScriptSource;
import app.morphe.extension.shared.spoof.js.nsigsolver.runtime.ScriptType;
import app.morphe.extension.shared.spoof.js.nsigsolver.runtime.ScriptVariant;

public class V8ChallengeProvider extends JsRuntimeChalBaseJCP {
    private static final V8ChallengeProvider INSTANCE = new V8ChallengeProvider();

    private final List<String> v8NpmLibFilename = Arrays.asList(
            LIB_PREFIX + "polyfill.js",
            LIB_PREFIX + "meriyah-6.1.4.min.js",
            LIB_PREFIX + "astring-1.9.0.min.js"
    );

    private ExecutorService v8Executor = Executors.newSingleThreadExecutor();
    private V8 v8Runtime;
    private int executeCount = 0;

    private V8ChallengeProvider() {}

    public static V8ChallengeProvider getInstance() {
        return INSTANCE;
    }

    // Override builtinSource to inject V8 specific logic
    @Override
    protected Script builtinSource(ScriptType scriptType) {
        // Try V8 specific source first for LIB
        if (scriptType == ScriptType.LIB) {
            Script v8Script = v8NpmSource(scriptType);
            if (v8Script != null) return v8Script;
        }
        return super.builtinSource(scriptType);
    }

    private Script v8NpmSource(ScriptType scriptType) {
        try {
            String code = ScriptUtils.loadScript(v8NpmLibFilename, "Failed to read v8 challenge solver lib script");
            return new Script(scriptType, ScriptVariant.V8_NPM, ScriptSource.BUILTIN, SCRIPT_VERSION, code);
        } catch (ScriptUtils.ScriptLoaderError e) {
            Logger.printException(() -> "Failed to read v8 npm source", e);
            return null;
        }
    }

    @Override
    protected String runJsRuntime(String stdin) throws JsChallengeProviderError {
        warmup();
        return runJS(stdin, false);
    }

    private String runJS(String stdin, boolean warmup) throws JsChallengeProviderError {
        try {
            String results = v8Executor.submit(() -> {
                // Null checking and setting in the v8 runtime are done on the same thread
                if (v8Runtime == null) {
                    v8Runtime = V8.createV8Runtime();
                }

                // Run js to get decipher results
                String result = v8Runtime.executeStringScript(stdin);

                // The decipher function and global functions are remembered by the V8 runtime's Bytecode Caching
                // This ensures that the V8 runtime is faster than other runtimes, such as QuickJS,
                // when deciphering dozens of formats simultaneously with yt-dlp-ejs
                //
                // To prevent memory leaks, the V8 runtime's cached bytecode must be periodically flushed
                // The V8 runtime is reset every time the execution count exceeds 12
                //
                // Note: There is a delay of approximately 100-200ms when the runtime is regenerated
                if (executeCount > 12 && !warmup && !v8Runtime.isReleased()) {
                    v8Runtime.lowMemoryNotification();
                    v8Runtime.release(false);
                    v8Runtime = null;
                    executeCount = 0;
                    Logger.printDebug(() -> "Close the V8 runtime");
                }
                executeCount++;

                return result;
            }).get();

            // The results of the warmup are not used anywhere
            if (warmup) {
                return "";
            }

            if (Utils.isNotEmpty(results)) {
                return results;
            } else {
                var message = "V8 runtime error: empty response";
                Logger.printException(() -> message);
                throw new JsChallengeProviderError(message);
            }
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof V8ScriptExecutionException v8e) {
                if (v8e.getMessage() != null && v8e.getMessage().contains("Invalid or unexpected token")) {
                    try {
                        cacheService.clear(CACHE_SECTION);
                    } catch (CacheError ce) {
                        // ignore
                    }
                }
                Logger.printException(() -> "V8 runtime error, warmup: " + warmup, v8e);
                throw new JsChallengeProviderError("V8 runtime error: " + v8e.getMessage(), v8e);
            }
            Logger.printException(() -> "Execution failed, warmup: " + warmup, e);
            throw new JsChallengeProviderError("Execution failed", e);
        }
    }

    public void warmup() {
        // If v8Executor terminates for an unexpected reason, it will be recreated
        if (v8Executor.isShutdown() || v8Executor.isTerminated()) {
            try {
                v8Executor = Executors.newSingleThreadExecutor();
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to create V8 executor", ex);
            }
        }

        try {
            // Declare a global function
            runJS(constructCommonStdin(), true);
        } catch (Exception e) {
            // ignore warmup errors
        }
    }
}
