package app.morphe.extension.shared.spoof.js;

import android.util.Pair;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.js.nsigsolver.impl.V8ChallengeProvider;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.*;

/**
 * The functions used in this class are referenced below:
 * - <a href="https://github.com/yuliskov/MediaServiceCore/blob/c0415f34ea59b2c35c8d72cdf21ff22d82218c1c/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/playerdata/PlayerDataExtractor.kt">yuliskov/MediaServiceCore#PlayerDataExtractor</a>
 */
public class PlayerDataExtractor {

    public PlayerDataExtractor(String playerJS, String playerJSHash) {
        V8ChallengeProvider.getInstance().setPlayerJS(playerJS, playerJSHash);
        V8ChallengeProvider.getInstance().warmup();

        checkAllData();
    }

    public Pair<List<String>, List<String>> bulkSigExtract(List<String> nParams, List<String> sParams) {
        List<String> nProcessed = new ArrayList<>();
        List<String> sProcessed = new ArrayList<>();

        List<JsChallengeRequest> requests = getJsChallengeRequests(nParams, sParams);
        List<JsChallengeProviderResponse> result = V8ChallengeProvider.getInstance().bulkSolve(requests);

        for (JsChallengeProviderResponse item : result) {
            var response = item.getResponse();
            if (response == null) continue;

            var type = response.getType();
            if (type == JsChallengeType.N) {
                if (nParams != null && !nParams.isEmpty()) {
                    for (String key : nParams) {
                        nProcessed.add(response.getOutput().getResults().get(key));
                    }
                }
            } else if (type == JsChallengeType.SIG) {
                if (sParams != null && !sParams.isEmpty()) {
                    for (String key : sParams) {
                        sProcessed.add(response.getOutput().getResults().get(key));
                    }
                }
            }
        }

        return new Pair<>(nProcessed, sProcessed);
    }

    @NonNull
    private static List<JsChallengeRequest> getJsChallengeRequests(List<String> nParams, List<String> sParams) {
        JsChallengeRequest nRequest = null;
        if (nParams != null && !nParams.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String s : nParams) {
                if (s != null && !s.isEmpty() && !filtered.contains(s)) {
                    filtered.add(s);
                }
            }
            if (!filtered.isEmpty()) {
                nRequest = new JsChallengeRequest(JsChallengeType.N, new ChallengeInput(filtered));
            }
        }

        JsChallengeRequest sRequest = null;
        if (sParams != null && !sParams.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String s : sParams) {
                if (s != null && !s.isEmpty() && !filtered.contains(s)) filtered.add(s);
            }
            if (!filtered.isEmpty()) {
                sRequest = new JsChallengeRequest(JsChallengeType.SIG, new ChallengeInput(filtered));
            }
        }

        List<JsChallengeRequest> requests = new ArrayList<>();
        if (nRequest != null) requests.add(nRequest);
        if (sRequest != null) requests.add(sRequest);
        return requests;
    }

    private void checkAllData() {
        List<String> param = Collections.singletonList("5cNpZqIJ7ixNqU68Y7S");

        try {
            List<JsChallengeRequest> requests = new ArrayList<>();
            requests.add(new JsChallengeRequest(JsChallengeType.N, new ChallengeInput(param)));
            requests.add(new JsChallengeRequest(JsChallengeType.SIG, new ChallengeInput(param)));

            V8ChallengeProvider.getInstance().bulkSolve(requests);
        } catch (Exception ex) {
            Logger.printException(() -> "Deobfuscation test failed", ex);
        }
    }
}