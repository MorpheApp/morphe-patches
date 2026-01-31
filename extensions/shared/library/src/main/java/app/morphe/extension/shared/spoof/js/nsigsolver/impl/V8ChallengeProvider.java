package app.morphe.extension.shared.spoof.js.nsigsolver.impl;

import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8ScriptExecutionException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
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

    private final ExecutorService v8Executor = Executors.newSingleThreadExecutor();
    private V8 v8Runtime;

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
            String result = v8Executor.submit(() -> {
                if (v8Runtime != null) {
                    return v8Runtime.executeStringScript(stdin);
                }
                return null;
            }).get();

            if (warmup) {
                return "";
            } else if (result == null || result.isEmpty()) {
                var message = "V8 runtime error: empty response";
                Logger.printException(() -> message);
                throw new JsChallengeProviderError(message);
            } else {
                return result;
            }
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof V8ScriptExecutionException) {
                V8ScriptExecutionException v8e = (V8ScriptExecutionException) cause;
                if (v8e.getMessage() != null && v8e.getMessage().contains("Invalid or unexpected token")) {
                    try {
                        cacheService.clear(CACHE_SECTION);
                    } catch (CacheError ce) {
                        // ignore
                    }
                }
                if (!warmup) {
                    shutDown();
                }
                Logger.printException(() -> "V8 runtime error, warmup: " + warmup, v8e);
                throw new JsChallengeProviderError("V8 runtime error: " + v8e.getMessage(), v8e);
            }
            Logger.printException(() -> "Execution failed, warmup: " + warmup, e);
            throw new JsChallengeProviderError("Execution failed", e);
        }
    }

    public void shutDown() {
        v8Executor.submit(() -> {
            if (v8Runtime != null) {
                v8Runtime.release(false);
                v8Runtime = null;
            }
        });
        v8Executor.shutdown();
    }

    public void warmup() {
        // Check needs to be inside executor or safe thread-check, but simplified here
        // We submit a task to check/create
        try {
            v8Executor.submit(() -> {
                if (v8Runtime == null) {
                    v8Runtime = V8.createV8Runtime();
                }
            }).get();
        } catch (Exception e) {
            // handle error
        }

        try {
            runJS(constructCommonStdin(), true);
        } catch (Exception e) {
            // ignore warmup errors
        }
    }
}