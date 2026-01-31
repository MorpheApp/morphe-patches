package app.morphe.extension.shared.spoof.js;

import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.js.nsigsolver.impl.V8ChallengeProvider;
import app.morphe.extension.shared.spoof.js.nsigsolver.provider.*;

/**
 * The functions used in this class are referenced below:
 * - <a href="https://github.com/yuliskov/MediaServiceCore/blob/c0415f34ea59b2c35c8d72cdf21ff22d82218c1c/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/playerdata/PlayerDataExtractor.kt">yuliskov/MediaServiceCore#PlayerDataExtractor</a>
 */
public class PlayerDataExtractor {
    private Pair<String, String> nSigTmp = null;

    public PlayerDataExtractor(String playerJS, String playerJSIdentifier) {
        V8ChallengeProvider.getInstance().setPlayerJS(playerJS, playerJSIdentifier);
        V8ChallengeProvider.getInstance().warmup();

        checkAllData();
    }

    public String extractNSig(String nParam) {
        if (nSigTmp != null && nSigTmp.first.equals(nParam)) {
            return nSigTmp.second;
        }

        String nSig = extractNSigReal(nParam);

        nSigTmp = new Pair<>(nParam, nSig);

        return nSig;
    }

    public String extractSig(String sParam) {
        List<JsChallengeRequest> requests = new ArrayList<>();
        requests.add(new JsChallengeRequest(JsChallengeType.SIG, new ChallengeInput(sParam)));

        List<JsChallengeProviderResponse> result = V8ChallengeProvider.getInstance().bulkSolve(requests);

        if (!result.isEmpty()) {
            var response = result.get(0).getResponse();
            if (response != null) {
                return response.getOutput().getResults().get(sParam);
            }
        }

        return null;
    }

    private String extractNSigReal(String nParam) {
        List<JsChallengeRequest> requests = new ArrayList<>();
        requests.add(new JsChallengeRequest(JsChallengeType.N, new ChallengeInput(nParam)));

        List<JsChallengeProviderResponse> result = V8ChallengeProvider.getInstance().bulkSolve(requests);

        if (!result.isEmpty()) {
            var response = result.get(0).getResponse();
            if (response != null) {
                return response.getOutput().getResults().get(nParam);
            }
        }

        return null;
    }

    private void checkAllData() {
        String param = "5cNpZqIJ7ixNqU68Y7S";

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