/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/340
 * https://github.com/MorpheApp/morphe-patches/pull/3655
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

import static app.morphe.extension.shared.Utils.isNotEmpty;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.js.JavaScriptManager;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.CacheError;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.CachedData;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.ScriptUtils;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.ChallengeOutput;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeProvider;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeProviderError;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeProviderRejectedRequest;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeProviderResponse;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeRequest;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeResponse;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.JsChallengeType;

public abstract class JsRuntimeChalBaseJCP extends JsChallengeProvider {
    protected static final String CACHE_SECTION = "challenge-solver";
    protected static final String SCRIPT_VERSION = "0.0.1";
    protected static final String LIB_PREFIX = "nsigsolver/";
    private static final String repository = "yt-dlp/ejs";

    private static final Type SOLVER_OUTPUT_TYPE = new TypeToken<SolverOutput>() {}.getType();

    private String playerJS = "";
    private String playerJSHash = "";

    private final Map<ScriptType, String> scriptFilenames;
    private final Map<ScriptType, String> minScriptFilenames;

    private Script libScript;
    private Script coreScript;
    private Script wrapperScript;

    // Track the current player hash loaded in the JS runtime
    private String loadedPlayerHash = "";

    // LRU Cache equivalent
    @GuardedBy("itself")
    protected final Map<String, String> cache = Utils.createSizeRestrictedMap(15, true);

    public JsRuntimeChalBaseJCP() {
        scriptFilenames = Map.of(
                ScriptType.LIB, LIB_PREFIX + "yt.solver.lib.js",
                ScriptType.CORE, LIB_PREFIX + "yt.solver.core.js",
                ScriptType.WRAPPER, LIB_PREFIX + "yt.solver.wrapper.js"
        );

        minScriptFilenames = Map.of(
                ScriptType.LIB, "yt.solver.lib.min.js",
                ScriptType.CORE, "yt.solver.core.min.js",
                ScriptType.WRAPPER, "yt.solver.wrapper.min.js"
        );
    }

    @Override
    protected List<JsChallengeType> getSupportedTypes() {
        return Arrays.asList(JsChallengeType.N, JsChallengeType.SIG);
    }

    protected abstract String runJsRuntime(String stdin) throws JsChallengeProviderError;

    public void setPlayerJS(String jsCode, String playerJSHash) {
        this.playerJS = jsCode;
        this.playerJSHash = playerJSHash;
    }

    /**
     * Reset the loaded player state. Call this when the JS runtime is reset
     * (e.g., V8 runtime is released) to force reloading the player on the next solve.
     */
    protected void resetLoadedPlayerState() {
        this.loadedPlayerHash = "";
    }

    @Override
    protected List<JsChallengeProviderResponse> realBulkSolve(List<JsChallengeRequest> requests) {
        List<JsChallengeProviderResponse> responses = new ArrayList<>();

        try {
            // Check if we need to load/reload the player into JS runtime
            final boolean playerChanged = !playerJSHash.equals(loadedPlayerHash);

            if (playerChanged) {
                // Try to get preprocessed player from cache
                String player = readPreprocessedPlayer(playerJSHash);
                final boolean preprocessed = (player != null);

                if (!preprocessed) {
                    player = playerJS;
                }

                // Load player into JS runtime wrapper
                String loadStdin = constructPlayerLoadStdin(player, playerJSHash, preprocessed);
                String loadResult = runJsRuntime(loadStdin);

                // Parse the load result to check for preprocessed player
                Gson gson = new Gson();
                try {
                    SolverOutput loadOutput = gson.fromJson(loadResult, SOLVER_OUTPUT_TYPE);
                    if (loadOutput != null && loadOutput.getPreprocessedPlayer() != null) {
                        savePreprocessedPlayer(playerJSHash, loadOutput.getPreprocessedPlayer());
                    }
                } catch (JsonSyntaxException ex) {
                    // Ignore parse errors for load result - the important thing is the player is loaded
                    Logger.printDebug(() -> "Could not parse player load result (non-fatal)");
                }

                loadedPlayerHash = playerJSHash;
            }

            // Now solve challenges using the wrapper (player is already in JS runtime memory)
            String stdin = constructWrapperStdin(requests);
            String stdout = runJsRuntime(stdin);

            Gson gson = new Gson();
            SolverOutput output;
            try {
                output = gson.fromJson(stdout, SOLVER_OUTPUT_TYPE);
            } catch (JsonSyntaxException ex) {
                Logger.printException(() -> "Cannot parse solver output", ex);
                throw new JsChallengeProviderError("Cannot parse solver output", ex);
            }

            if ("error".equals(output.getType())) {
                String message = output.getError() != null ? output.getError() : "Unknown solver output error";
                throw new JsChallengeProviderError(message);
            }

            List<ResponseData> outputResponses = output.getResponses();
            if (outputResponses != null && outputResponses.size() == requests.size()) {
                for (int i = 0, size = requests.size(); i < size; i++) {
                    JsChallengeRequest request = requests.get(i);
                    ResponseData responseData = outputResponses.get(i);

                    if ("error".equals(responseData.getType())) {
                        String message = responseData.getError() != null ? responseData.getError() : "Unknown solver output error";

                        responses.add(new JsChallengeProviderResponse(
                                request,
                                new JsChallengeProviderError(message)
                        ));
                    } else {
                        responses.add(new JsChallengeProviderResponse(
                                request,
                                new JsChallengeResponse(request.getType(), new ChallengeOutput(responseData.getData()))
                        ));
                    }
                }
            }
        } catch (Exception ex) {
            // If any global error occurs, fail all requests
            for (JsChallengeRequest request : requests) {
                responses.add(new JsChallengeProviderResponse(request, ex));
            }
            Logger.printException(() -> "BulkSolve failed", ex);
        }

        return responses;
    }

    /**
     * The preprocessed player is a few MB, too large for SharedPreferences,
     * which keep the whole file in memory and rewrite it on every save.
     */
    private static final String PREPROCESSED_PLAYER_PREFIX = "player_js_preprocessed_";
    private volatile boolean legacyPlayerCacheRemoved;

    private static File getPreprocessedPlayerFile(String playerHash) {
        return new File(Utils.getContext().getCacheDir(), PREPROCESSED_PLAYER_PREFIX + playerHash + ".js");
    }

    @Nullable
    private String readPreprocessedPlayer(String playerHash) {
        if (!legacyPlayerCacheRemoved) {
            legacyPlayerCacheRemoved = true;
            try {
                cacheService.removePlayerEntries(CACHE_SECTION);
            } catch (CacheError ex) {
                Logger.printDebug(() -> "Ignoring legacy player cache error", ex);
            }
        }

        File file = getPreprocessedPlayerFile(playerHash);
        if (!file.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read preprocessed player", ex);
            return null;
        }
    }

    private static void savePreprocessedPlayer(String playerHash, String player) {
        File file = getPreprocessedPlayerFile(playerHash);
        // A partly written file would be loaded as broken JavaScript, so write it fully before renaming.
        File temp = new File(file.getPath() + ".tmp");
        try {
            Files.write(temp.toPath(), player.getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(file)) {
                throw new IOException("Could not rename " + temp);
            }
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to save preprocessed player", ex);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }

        File[] files = file.getParentFile().listFiles((dir, name) ->
                name.startsWith(PREPROCESSED_PLAYER_PREFIX) && !name.equals(file.getName()));
        if (files != null) {
            for (File old : files) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        }
    }

    protected static void clearPreprocessedPlayer(String playerHash) {
        //noinspection ResultOfMethodCallIgnored
        getPreprocessedPlayerFile(playerHash).delete();
    }

    protected String getPlayerJSHash() {
        return playerJSHash;
    }

    /**
     * Constructs stdin to load a player into the JS wrapper.
     * This is called when the player changes or on first solve.
     */
    private String constructPlayerLoadStdin(String playerJS, String playerHash, boolean preprocessed) {
        Gson gson = new Gson();
        String escapedPlayer = gson.toJson(playerJS);
        String escapedHash = gson.toJson(playerHash);

        if (preprocessed) {
            // Use setPreprocessedPlayer for already-preprocessed player
            return String.format("""
                    setPreprocessedPlayer(%s, %s);
                    JSON.stringify({type: 'result', responses: []});
                """,
                escapedPlayer, escapedHash
            );
        }
        // Use setPlayer for raw player, then do a dummy solve to trigger preprocessing
        // Do a minimal solve to trigger preprocessing and get the preprocessed player back
        return String.format("""
                setPlayer(%s, %s);
                JSON.stringify((function() {
                  var result = jscw({requests: [{type: 'n', challenges: []}]});
                  var pp = getPreprocessedPlayer();
                  if (pp) result.preprocessed_player = pp;
                  return result;
                })());
            """,
            escapedPlayer, escapedHash
        );
    }

    /**
     * Constructs stdin to solve challenges using the wrapper.
     * The player is already loaded in the JS runtime.
     */
    private String constructWrapperStdin(List<JsChallengeRequest> requests) {
        List<Map<String, Object>> jsonRequests = new ArrayList<>(requests.size());
        for (JsChallengeRequest request : requests) {
            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("type", request.getType().getValue());
            reqMap.put("challenges", request.getInput().getChallenges());
            jsonRequests.add(reqMap);
        }

        Gson gson = new Gson();
        Map<String, Object> data = Map.of("requests", jsonRequests);
        String jsonData = gson.toJson(data);
        return String.format("\nJSON.stringify(jscw(%s));\n", jsonData);
    }

    protected String constructCommonStdin() {
        try {
            return String.join("\n",
                    getLibScript().getCode(),
                    getCoreScript().getCode(),
                    getWrapperScript().getCode(),
                    "\"\";"
            );
        } catch (JsChallengeProviderRejectedRequest ex) {
            Logger.printException(() -> "Failed to construct stdin", ex);
            return "";
        }
    }

    private Script getLibScript() throws JsChallengeProviderRejectedRequest {
        if (libScript == null) {
            libScript = getScript(ScriptType.LIB);
        }
        return libScript;
    }

    private Script getCoreScript() throws JsChallengeProviderRejectedRequest {
        if (coreScript == null) {
            coreScript = getScript(ScriptType.CORE);
        }
        return coreScript;
    }

    private Script getWrapperScript() throws JsChallengeProviderRejectedRequest {
        if (wrapperScript == null) {
            wrapperScript = getScript(ScriptType.WRAPPER);
        }
        return wrapperScript;
    }

    private interface ScriptSourceProvider {
        Script get(ScriptType type);
    }

    // This replicates the iterator sequence in Kotlin
    private Script getScript(ScriptType scriptType) throws JsChallengeProviderRejectedRequest {
        // Strategy pattern for sources
        ScriptSourceProvider[] providers = new ScriptSourceProvider[] {
                this::cachedSource,
                this::builtinSource,
                this::webReleaseSource
        };

        for (ScriptSourceProvider provider : providers) {
            Script script = provider.get(scriptType);
            if (script == null) continue;

            if (!SCRIPT_VERSION.equals(script.getVersion())) {
                Logger.printDebug(() -> "Challenge solver: " + scriptType.getValue()
                        + " script version: " + script.getVersion() +
                        " is not supported (source: " + script.getSource().getValue()
                        + ", supported version: " + SCRIPT_VERSION + ")");
                continue;
            }

            Logger.printDebug(() -> "Using challenge solver: " + script.getType().getValue()
                    + " script: v" + script.getVersion() +
                    " (source: " + script.getSource().getValue()
                    + ", variant: " + script.getVariant().getValue() + ")");
            return script;
        }
        throw new JsChallengeProviderRejectedRequest("No usable challenge solver " + scriptType.getValue() + " script available");
    }

    private Script cachedSource(ScriptType scriptType) {
        try {
            CachedData data = cacheService.get(CACHE_SECTION, scriptType.getValue());
            if (data == null) return null;

            return new Script(
                    scriptType,
                    ScriptVariant.fromString(data.getVariant()),
                    ScriptSource.CACHE,
                    data.getVersion() != null ? data.getVersion() : "unknown",
                    data.getCode()
            );
        } catch (CacheError e) {
            return null;
        }
    }

    protected Script builtinSource(ScriptType scriptType) {
        String fileName = scriptFilenames.get(scriptType);
        if (isNotEmpty(fileName)) {
            try {
                String code = ScriptUtils.loadScript(fileName,
                        "Failed to read builtin challenge solver " + scriptType.getValue());
                return new Script(
                        scriptType,
                        ScriptVariant.UNMINIFIED,
                        ScriptSource.BUILTIN,
                        SCRIPT_VERSION,
                        code
                );
            } catch (ScriptUtils.ScriptLoaderError ex) {
                Logger.printDebug(() -> "Coud not load script", ex);
                return null;
            }
        }

        return null;
    }

    private Script webReleaseSource(ScriptType scriptType) {
        String fileName = minScriptFilenames.get(scriptType);
        if (isNotEmpty(fileName)) {
            synchronized (cache) {
                String code = cache.get(fileName);
                if (code == null) {
                    String url = "https://github.com/" + repository + "/releases/download/"
                            + SCRIPT_VERSION + "/" + fileName;
                    code = JavaScriptManager.downloadUrl(url);
                    Logger.printDebug(() -> "Downloading challenge solver: "
                            + scriptType.getValue()+ " script from: " + url);

                    if (isNotEmpty(code)) {
                        cache.put(fileName, code);
                        try {
                            cacheService.save(CACHE_SECTION, scriptType.getValue(), new CachedData(code));
                        } catch (CacheError ex) {
                            Logger.printException(() -> "Failed to save to cache", ex);
                        }
                    } else {
                        return null;
                    }
                }
                return new Script(
                        scriptType,
                        ScriptVariant.MINIFIED,
                        ScriptSource.WEB,
                        SCRIPT_VERSION,
                        code
                );
            }
        }

        return null;
    }
}