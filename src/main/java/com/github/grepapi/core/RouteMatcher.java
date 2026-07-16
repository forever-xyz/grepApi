package com.github.grepapi.core;

import com.github.grepapi.model.ApiMatchResult;
import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.model.ApiRouteMatch;
import com.github.grepapi.model.ApiSearchRequest;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RouteMatcher {
    private static final Comparator<ApiRouteMatch> WORST_FIRST = Comparator
            .comparingInt(ApiRouteMatch::score)
            .thenComparing(match -> match.route().path(), Comparator.reverseOrder());

    public @NotNull List<ApiRouteMatch> match(
            @NotNull ApiSearchRequest request,
            @NotNull List<ApiRoute> routes
    ) {
        return search(request, routes, Integer.MAX_VALUE).matches();
    }

    public @NotNull ApiMatchResult search(
            @NotNull ApiSearchRequest request,
            @NotNull List<ApiRoute> routes,
            int limit
    ) {
        int safeLimit = Math.max(1, limit);
        PriorityQueue<ApiRouteMatch> topMatches = new PriorityQueue<>(
                Math.min(safeLimit, 128),
                WORST_FIRST
        );
        int totalMatches = 0;

        for (ApiRoute route : routes) {
            if (!matchesHttpMethod(request.httpMethod(), route)) {
                continue;
            }

            ApiRouteMatch match = findBestMatch(request, route);
            if (match == null) {
                continue;
            }
            totalMatches++;

            if (topMatches.size() < safeLimit) {
                topMatches.add(match);
            } else if (WORST_FIRST.compare(match, topMatches.peek()) > 0) {
                topMatches.poll();
                topMatches.add(match);
            }
        }

        List<ApiRouteMatch> results = new ArrayList<>(topMatches);
        Collections.sort(results);
        return new ApiMatchResult(List.copyOf(results), totalMatches);
    }

    private ApiRouteMatch findBestMatch(@NotNull ApiSearchRequest request, @NotNull ApiRoute route) {
        ApiRouteMatch exact = findPathMatch(request, route);
        if (exact != null) {
            return exact;
        }
        if (request.path() != null) {
            return findPathContainsMatch(request, route);
        }
        return findFuzzyMatch(request, route);
    }

    private ApiRouteMatch findPathContainsMatch(
            @NotNull ApiSearchRequest request,
            @NotNull ApiRoute route
    ) {
        String routePath = UrlInputParser.normalizePath(route.path()).toLowerCase(Locale.ROOT);
        ApiRouteMatch best = null;
        for (int candidateIndex = 0; candidateIndex < request.candidatePaths().size(); candidateIndex++) {
            String candidate = UrlInputParser.normalizePath(request.candidatePaths().get(candidateIndex));
            String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
            int matchIndex = routePath.indexOf(normalizedCandidate);
            if (matchIndex < 0) {
                continue;
            }

            int score = (matchIndex == 0 ? 760 : 700)
                    + Math.min(100, normalizedCandidate.length() * 3)
                    - (candidateIndex * 35);
            if (request.httpMethod() != null && route.httpMethods().contains(request.httpMethod())) {
                score += 80;
            }
            ApiRouteMatch current = new ApiRouteMatch(
                    route,
                    score,
                    candidate,
                    candidateIndex,
                    matchIndex == 0 ? "路径前缀匹配" : "路径包含匹配"
            );
            if (best == null || current.score() > best.score()) {
                best = current;
            }
        }
        return best;
    }

    private ApiRouteMatch findPathMatch(@NotNull ApiSearchRequest request, @NotNull ApiRoute route) {
        ApiRouteMatch best = null;
        for (int candidateIndex = 0; candidateIndex < request.candidatePaths().size(); candidateIndex++) {
            String candidate = request.candidatePaths().get(candidateIndex);
            MatchDetails details = matchesPath(route.path(), candidate);
            if (!details.matches()) {
                continue;
            }

            int score = details.score() - (candidateIndex * 35);
            if (request.httpMethod() != null && route.httpMethods().contains(request.httpMethod())) {
                score += 80;
            }
            ApiRouteMatch current = new ApiRouteMatch(
                    route,
                    score,
                    request.searchText(),
                    candidateIndex,
                    buildPathReason(details, candidateIndex, request.httpMethod())
            );
            if (best == null || current.score() > best.score()) {
                best = current;
            }
        }
        return best;
    }

    private ApiRouteMatch findFuzzyMatch(@NotNull ApiSearchRequest request, @NotNull ApiRoute route) {
        String query = compact(request.searchText());
        if (query.isEmpty()) {
            int score = request.httpMethod() == null ? 100 : 180;
            return new ApiRouteMatch(route, score, request.searchText(), 0, "全部接口");
        }

        FieldScore best = bestFuzzyField(query, route);
        if (best.score() < 0) {
            return null;
        }

        int score = best.score();
        if (request.httpMethod() != null && route.httpMethods().contains(request.httpMethod())) {
            score += 80;
        }
        return new ApiRouteMatch(route, score, request.searchText(), 0, best.reason());
    }

    private @NotNull FieldScore bestFuzzyField(@NotNull String query, @NotNull ApiRoute route) {
        FieldScore best = scoreField(query, route.path(), 650, "路径模糊匹配");
        best = max(best, scoreField(query, route.methodName(), 590, "方法名模糊匹配"));
        best = max(best, scoreField(query, route.simpleClassName(), 540, "Controller 模糊匹配"));
        best = max(best, scoreField(query, route.moduleName(), 470, "模块名模糊匹配"));

        String combined = route.path() + " " + route.simpleClassName() + " "
                + route.methodName() + " " + route.moduleName();
        int subsequence = subsequenceScore(query, compact(combined));
        if (subsequence >= 0) {
            best = max(best, new FieldScore(330 + subsequence, "综合模糊匹配"));
        }
        return best;
    }

    private @NotNull FieldScore scoreField(
            @NotNull String compactQuery,
            @NotNull String value,
            int directBase,
            @NotNull String reason
    ) {
        String compactValue = compact(value);
        int directIndex = compactValue.indexOf(compactQuery);
        if (directIndex >= 0) {
            int boundaryBonus = directIndex == 0 ? 45 : 0;
            int lengthBonus = Math.min(60, compactQuery.length() * 6);
            return new FieldScore(directBase + boundaryBonus + lengthBonus - directIndex, reason);
        }

        int subsequence = subsequenceScore(compactQuery, compactValue);
        return subsequence < 0
                ? new FieldScore(-1, reason)
                : new FieldScore(directBase - 180 + subsequence, reason);
    }

    private int subsequenceScore(@NotNull String query, @NotNull String target) {
        if (query.isEmpty()) {
            return 0;
        }
        int queryIndex = 0;
        int previousMatch = -2;
        int score = 0;
        for (int targetIndex = 0; targetIndex < target.length() && queryIndex < query.length(); targetIndex++) {
            if (target.charAt(targetIndex) != query.charAt(queryIndex)) {
                continue;
            }
            score += targetIndex == previousMatch + 1 ? 14 : 5;
            if (targetIndex == 0) {
                score += 12;
            }
            previousMatch = targetIndex;
            queryIndex++;
        }
        if (queryIndex != query.length()) {
            return -1;
        }
        return score - Math.min(80, Math.max(0, target.length() - query.length()) / 3);
    }

    private @NotNull String compact(@NotNull String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private @NotNull FieldScore max(@NotNull FieldScore left, @NotNull FieldScore right) {
        return right.score() > left.score() ? right : left;
    }

    private boolean matchesHttpMethod(String inputMethod, @NotNull ApiRoute route) {
        return inputMethod == null || route.httpMethods().isEmpty() || route.httpMethods().contains(inputMethod);
    }

    private @NotNull MatchDetails matchesPath(@NotNull String routePath, @NotNull String inputPath) {
        String normalizedRoute = UrlInputParser.normalizePath(routePath);
        String normalizedInput = UrlInputParser.normalizePath(inputPath);
        if (normalizedRoute.equals(normalizedInput)) {
            return new MatchDetails(true, 1_000, 0, 0);
        }

        String[] segments = normalizedRoute.equals("/")
                ? new String[0]
                : normalizedRoute.substring(1).split("/");
        StringBuilder regex = new StringBuilder("^");
        int variables = 0;
        int wildcards = 0;
        int staticSegments = 0;

        if (segments.length == 0) {
            regex.append('/');
        }

        for (String segment : segments) {
            if ("**".equals(segment)) {
                regex.append("(?:/.*)?");
                wildcards += 2;
            } else if ("*".equals(segment)) {
                regex.append("/[^/]+");
                wildcards++;
            } else if (segment.startsWith("{") && segment.endsWith("}")) {
                int colon = segment.indexOf(':');
                if (colon > 1) {
                    String expression = segment.substring(colon + 1, segment.length() - 1);
                    regex.append("/(?:").append(expression).append(')');
                } else {
                    regex.append("/[^/]+");
                }
                variables++;
            } else {
                regex.append('/').append(Pattern.quote(segment));
                staticSegments++;
            }
        }
        regex.append("/?$");

        try {
            boolean matched = Pattern.compile(regex.toString()).matcher(normalizedInput).matches();
            int score = 800 + (staticSegments * 20) - (variables * 8) - (wildcards * 45);
            return new MatchDetails(matched, score, variables, wildcards);
        } catch (PatternSyntaxException ignored) {
            return new MatchDetails(false, 0, variables, wildcards);
        }
    }

    private @NotNull String buildPathReason(@NotNull MatchDetails details, int removedSegments, String method) {
        List<String> reasons = new ArrayList<>();
        if (details.variables() == 0 && details.wildcards() == 0) {
            reasons.add("路径完全匹配");
        } else if (details.variables() > 0) {
            reasons.add("路径变量匹配");
        } else {
            reasons.add("通配符匹配");
        }
        if (method != null) {
            reasons.add(method.toUpperCase(Locale.ROOT) + " 方法匹配");
        }
        if (removedSegments > 0) {
            reasons.add("已移除前缀");
        }
        return String.join(" · ", reasons);
    }

    private record MatchDetails(boolean matches, int score, int variables, int wildcards) {
    }

    private record FieldScore(int score, @NotNull String reason) {
    }
}
