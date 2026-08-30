/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.requests;

public class Route {
    public enum Method {
        GET,
        POST
    }

    private final String route;
    private final Method method;
    private final int paramCount;

    public Route(Method method, String route) {
        this.method = method;
        this.route = route;
        this.paramCount = countMatches(route, '{');

        if (paramCount != countMatches(route, '}')) {
            throw new IllegalArgumentException("Not enough parameters");
        }
    }

    public Method getMethod() {
        return method;
    }

    public CompiledRoute compile(String... params) {
        if (params.length != paramCount)
            throw new IllegalArgumentException("Error compiling route [" + route + "]. "
                    + "Incorrect amount of parameters provided, expected: " + paramCount
                    + " provided: " + params.length);

        StringBuilder compiledRoute = new StringBuilder();
        int lastEnd = 0;
        for (int i = 0; i < paramCount; i++) {
            int paramStart = route.indexOf("{", lastEnd);
            int paramEnd = route.indexOf("}", paramStart);
            compiledRoute.append(route, lastEnd, paramStart);
            compiledRoute.append(params[i]);
            lastEnd = paramEnd + 1;
        }
        compiledRoute.append(route.substring(lastEnd));
        return new CompiledRoute(this, compiledRoute.toString());
    }

    public static class CompiledRoute {
        private final Route baseRoute;
        private final String compiledRoute;

        private CompiledRoute(Route baseRoute, String compiledRoute) {
            this.baseRoute = baseRoute;
            this.compiledRoute = compiledRoute;
        }

        public String getCompiledRoute() {
            return compiledRoute;
        }

        Method getMethod() {
            return baseRoute.method;
        }
    }

    private int countMatches(CharSequence seq, char c) {
        int count = 0;
        for (int i = 0, length = seq.length(); i < length; i++) {
            if (seq.charAt(i) == c)
                count++;
        }
        return count;
    }
}