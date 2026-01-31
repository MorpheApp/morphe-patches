package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

import static app.morphe.extension.shared.Utils.isNotEmpty;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.js.JavaScriptManager;
import app.morphe.extension.shared.spoof.js.nsigsolver.common.*;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.*;

public abstract class JsRuntimeChalBaseJCP extends JsChallengeProvider {
    public static final String CACHE_SECTION = "challenge-solver";
    protected static final String SCRIPT_VERSION = "0.0.1";
    public static final String LIB_PREFIX = "nsigsolver/";
    private static final Type SOLVER_OUTPUT_TYPE = new TypeToken<SolverOutput>() {}.getType();

    private String playerJS = "";
    private String playerJSIdentifier = "";
    private final String repository = "yt-dlp/ejs";

    private final Map<ScriptType, String> scriptFilenames;
    private final Map<ScriptType, String> minScriptFilenames;

    private Script libScript;
    private Script coreScript;

    // LRU Cache equivalent
    protected final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(30, 0.75f, true) {
                private static final int CACHE_LIMIT = 15;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_LIMIT;
                }
            }
    );

    public JsRuntimeChalBaseJCP() {
        Map<ScriptType, String> sMap = new HashMap<>();
        sMap.put(ScriptType.LIB, LIB_PREFIX + "yt.solver.lib.js");
        sMap.put(ScriptType.CORE, LIB_PREFIX + "yt.solver.core.js");
        scriptFilenames = Collections.unmodifiableMap(sMap);

        Map<ScriptType, String> mMap = new HashMap<>();
        mMap.put(ScriptType.LIB, "yt.solver.lib.min.js");
        mMap.put(ScriptType.CORE, "yt.solver.core.min.js");
        minScriptFilenames = Collections.unmodifiableMap(mMap);
    }

    @Override
    protected List<JsChallengeType> getSupportedTypes() {
        return Arrays.asList(JsChallengeType.N, JsChallengeType.SIG);
    }

    protected abstract String runJsRuntime(String stdin) throws JsChallengeProviderError;

    public void setPlayerJS(String jsCode, String jsIdentifier) {
        this.playerJS = jsCode;
        this.playerJSIdentifier = jsIdentifier;
    }

    @Override
    protected List<JsChallengeProviderResponse> realBulkSolve(List<JsChallengeRequest> requests) {
        List<JsChallengeProviderResponse> responses = new ArrayList<>();

        try {
            CachedData data = cacheService.get(CACHE_SECTION, "player:" + playerJSIdentifier);
            String player = (data != null) ? data.getCode() : null;
            boolean cached;

            if (player != null) {
                cached = true;
            } else {
                player = playerJS;
                cached = false;
            }

            String stdin = constructStdin(player, cached, requests);
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

            String preprocessed = output.getPreprocessedPlayer();
            if (preprocessed != null) {
                cacheService.save(CACHE_SECTION, "player:" + playerJSIdentifier, new CachedData(preprocessed));
            }

            List<ResponseData> outputResponses = output.getResponses();
            if (outputResponses != null && outputResponses.size() == requests.size()) {
                for (int i = 0; i < requests.size(); i++) {
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

    private String constructStdin(String playerJS, boolean preprocessed, List<JsChallengeRequest> requests) {
        List<Map<String, Object>> jsonRequests = new ArrayList<>();
        for (JsChallengeRequest request : requests) {
            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("type", request.getType().getValue());
            reqMap.put("challenges", Collections.singletonList(request.getInput().getChallenge()));
            jsonRequests.add(reqMap);
        }

        Map<String, Object> data = new HashMap<>();
        if (preprocessed) {
            data.put("type", "preprocessed");
            data.put("preprocessed_player", playerJS);
            data.put("requests", jsonRequests);
        } else {
            data.put("type", "player");
            data.put("player", playerJS);
            data.put("requests", jsonRequests);
            data.put("output_preprocessed", true);
        }

        Gson gson = new Gson();
        String jsonData = gson.toJson(data);
        return String.format("\nJSON.stringify(jsc(%s));\n", jsonData);
    }

    protected String constructCommonStdin() {
        try {
            return String.join("\n",
                    getLibScript().getCode(),
                    getCoreScript().getCode(),
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
                Logger.printDebug(() -> "Challenge solver " + scriptType.getValue() + " script version " + script.getVersion() +
                        " is not supported (source: " + script.getSource().getValue() + ", supported version: " + SCRIPT_VERSION + ")");
                continue;
            }

            Logger.printDebug(() -> "Using challenge solver " + script.getType().getValue() + " script v" + script.getVersion() +
                    " (source: " + script.getSource().getValue() + ", variant: " + script.getVariant().getValue() + ")");
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
                String code = ScriptUtils.loadScript(fileName, "Failed to read builtin challenge solver " + scriptType.getValue());
                return new Script(
                        scriptType,
                        ScriptVariant.UNMINIFIED,
                        ScriptSource.BUILTIN,
                        SCRIPT_VERSION,
                        code
                );
            } catch (ScriptUtils.ScriptLoaderError e) {
                return null;
            }
        } else {
            return null;
        }
    }

    private Script webReleaseSource(ScriptType scriptType) {
        String fileName = minScriptFilenames.get(scriptType);
        if (isNotEmpty(fileName)) {
            synchronized (cache) {
                String code = cache.get(fileName);
                if (code == null) {
                    String url = "https://github.com/" + repository + "/releases/download/" + SCRIPT_VERSION + "/" + fileName;
                    code = JavaScriptManager.downloadUrl(url);
                    Logger.printDebug(() -> "Downloading challenge solver " + scriptType.getValue() + " script from " + url);

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
        } else {
            return null;
        }
    }
}