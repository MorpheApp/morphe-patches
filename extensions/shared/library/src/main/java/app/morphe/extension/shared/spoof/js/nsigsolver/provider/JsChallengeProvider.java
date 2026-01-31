package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

import app.morphe.extension.shared.spoof.js.nsigsolver.common.CacheService;

import java.util.ArrayList;
import java.util.List;

public abstract class JsChallengeProvider {
    protected final CacheService cacheService = CacheService.getInstance();

    protected abstract List<JsChallengeType> getSupportedTypes();

    private void validateRequest(JsChallengeRequest request) throws JsChallengeProviderRejectedRequest {
        if (!getSupportedTypes().contains(request.getType())) {
            throw new JsChallengeProviderRejectedRequest(
                    "JS Challenge type " + request.getType() + " is not supported by the provider " + this.getClass().getSimpleName()
            );
        }
    }

    /**
     * Solve multiple JS challenges and return the results
     */
    public List<JsChallengeProviderResponse> bulkSolve(List<JsChallengeRequest> requests) {
        List<JsChallengeProviderResponse> responses = new ArrayList<>();
        List<JsChallengeRequest> validatedRequests = new ArrayList<>();

        for (JsChallengeRequest request : requests) {
            try {
                validateRequest(request);
                validatedRequests.add(request);
            } catch (JsChallengeProviderRejectedRequest e) {
                responses.add(new JsChallengeProviderResponse(request, e));
            }
        }

        // Add results from real implementation
        responses.addAll(realBulkSolve(validatedRequests));

        return responses;
    }

    /**
     * Subclasses can override this method to handle bulk solving
     */
    protected abstract List<JsChallengeProviderResponse> realBulkSolve(List<JsChallengeRequest> requests);
}