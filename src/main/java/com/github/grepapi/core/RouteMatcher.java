package com.github.grepapi.core;

import com.github.grepapi.model.ApiMatchResult;
import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.model.ApiRouteMatch;
import com.github.grepapi.model.ApiSearchRequest;
import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RouteMatcher {
    private static final int CANCELLATION_CHECK_INTERVAL = 64;
    private static final int MAX_CACHED_ROUTES = 8_000;
    private static final Comparator<ApiRouteMatch> WORST_FIRST = Comparator
            .comparingInt(ApiRouteMatch::score)
            .thenComparing(match -> match.route().path(), Comparator.reverseOrder());
    private final ConcurrentHashMap<ApiRoute, RouteData> routeDataCache = new ConcurrentHashMap<>();

    public void clearCache() {
        routeDataCache.clear();
    }

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
        SearchRequestData requestData = SearchRequestData.from(request);
        int safeLimit = Math.max(1, limit);
        PriorityQueue<ApiRouteMatch> topMatches = new PriorityQueue<>(
                Math.min(safeLimit, 128),
                WORST_FIRST
        );
        int totalMatches = 0;

        for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
            if (routeIndex % CANCELLATION_CHECK_INTERVAL == 0) {
                ProgressManager.checkCanceled();
            }
            ApiRoute route = routes.get(routeIndex);
            if (!matchesHttpMethod(request.httpMethod(), route)) {
                continue;
            }

            ApiRouteMatch match = findBestMatch(request, requestData, route, routeDataFor(route));
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

    private @NotNull RouteData routeDataFor(@NotNull ApiRoute route) {
        RouteData cached = routeDataCache.get(route);
        if (cached != null) {
            return cached;
        }

        RouteData created = RouteData.from(route);
        if (routeDataCache.size() >= MAX_CACHED_ROUTES) {
            return created;
        }
        RouteData existing = routeDataCache.putIfAbsent(route, created);
        return existing == null ? created : existing;
    }

    private ApiRouteMatch findBestMatch(
            @NotNull ApiSearchRequest request,
            @NotNull SearchRequestData requestData,
            @NotNull ApiRoute route,
            @NotNull RouteData routeData
    ) {
        ApiRouteMatch exact = findPathMatch(request, requestData, route, routeData);
        if (exact != null) {
            return exact;
        }
        if (request.path() != null) {
            return findPathContainsMatch(request, requestData, route, routeData);
        }
        return findFuzzyMatch(request, requestData, route, routeData);
    }

    private ApiRouteMatch findPathContainsMatch(
            @NotNull ApiSearchRequest request,
            @NotNull SearchRequestData requestData,
            @NotNull ApiRoute route,
            @NotNull RouteData routeData
    ) {
        ApiRouteMatch best = null;
        for (int candidateIndex = 0; candidateIndex < requestData.pathCandidates().size(); candidateIndex++) {
            PathCandidate candidate = requestData.pathCandidates().get(candidateIndex);
            int matchIndex = routeData.lowerCasePath().indexOf(candidate.lowerCasePath());
            if (matchIndex < 0) {
                continue;
            }

            int score = (matchIndex == 0 ? 760 : 700)
                    + Math.min(100, candidate.normalizedPath().length() * 3)
                    - (candidateIndex * 35);
            if (request.httpMethod() != null && route.httpMethods().contains(request.httpMethod())) {
                score += 80;
            }
            ApiRouteMatch current = new ApiRouteMatch(
                    route,
                    score,
                    candidate.normalizedPath(),
                    candidateIndex,
                    matchIndex == 0 ? "路径前缀匹配" : "路径包含匹配"
            );
            if (best == null || current.score() > best.score()) {
                best = current;
            }
        }
        return best;
    }

    private ApiRouteMatch findPathMatch(
            @NotNull ApiSearchRequest request,
            @NotNull SearchRequestData requestData,
            @NotNull ApiRoute route,
            @NotNull RouteData routeData
    ) {
        ApiRouteMatch best = null;
        for (int candidateIndex = 0; candidateIndex < requestData.pathCandidates().size(); candidateIndex++) {
            PathCandidate candidate = requestData.pathCandidates().get(candidateIndex);
            MatchDetails details = routeData.pathTemplate().matches(candidate.normalizedPath());
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

    private ApiRouteMatch findFuzzyMatch(
            @NotNull ApiSearchRequest request,
            @NotNull SearchRequestData requestData,
            @NotNull ApiRoute route,
            @NotNull RouteData routeData
    ) {
        String query = requestData.compactSearchText();
        if (query.isEmpty()) {
            int score = request.httpMethod() == null ? 100 : 180;
            return new ApiRouteMatch(route, score, request.searchText(), 0, "全部接口");
        }

        FieldScore best = bestFuzzyField(query, routeData);
        if (best.score() < 0) {
            return null;
        }

        int score = best.score();
        if (request.httpMethod() != null && route.httpMethods().contains(request.httpMethod())) {
            score += 80;
        }
        return new ApiRouteMatch(route, score, request.searchText(), 0, best.reason());
    }

    private @NotNull FieldScore bestFuzzyField(@NotNull String query, @NotNull RouteData routeData) {
        FieldScore best = scoreField(query, routeData.compactPath(), 650, "路径模糊匹配");
        best = max(best, scoreField(query, routeData.compactMethodName(), 590, "方法名模糊匹配"));
        best = max(best, scoreField(query, routeData.compactClassName(), 540, "Controller 模糊匹配"));
        best = max(best, scoreField(query, routeData.compactModuleName(), 470, "模块名模糊匹配"));

        int subsequence = subsequenceScore(query, routeData.compactCombined());
        if (subsequence >= 0) {
            best = max(best, new FieldScore(330 + subsequence, "综合模糊匹配"));
        }
        return best;
    }

    private @NotNull FieldScore scoreField(
            @NotNull String compactQuery,
            @NotNull String compactValue,
            int directBase,
            @NotNull String reason
    ) {
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

    private static @NotNull String compact(@NotNull String value) {
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

    private record SearchRequestData(
            @NotNull String compactSearchText,
            @NotNull List<PathCandidate> pathCandidates
    ) {
        private static @NotNull SearchRequestData from(@NotNull ApiSearchRequest request) {
            List<PathCandidate> candidates = new ArrayList<>(request.candidatePaths().size());
            for (String path : request.candidatePaths()) {
                String normalizedPath = UrlInputParser.normalizePath(path);
                candidates.add(new PathCandidate(normalizedPath, normalizedPath.toLowerCase(Locale.ROOT)));
            }
            return new SearchRequestData(compact(request.searchText()), List.copyOf(candidates));
        }
    }

    private record PathCandidate(@NotNull String normalizedPath, @NotNull String lowerCasePath) {
    }

    private record RouteData(
            @NotNull String lowerCasePath,
            @NotNull String compactPath,
            @NotNull String compactMethodName,
            @NotNull String compactClassName,
            @NotNull String compactModuleName,
            @NotNull String compactCombined,
            @NotNull PathTemplate pathTemplate
    ) {
        private static @NotNull RouteData from(@NotNull ApiRoute route) {
            String normalizedPath = UrlInputParser.normalizePath(route.path());
            String simpleClassName = route.simpleClassName();
            String moduleName = route.moduleName();
            return new RouteData(
                    normalizedPath.toLowerCase(Locale.ROOT),
                    compact(normalizedPath),
                    compact(route.methodName()),
                    compact(simpleClassName),
                    compact(moduleName),
                    compact(normalizedPath + " " + simpleClassName + " " + route.methodName() + " " + moduleName),
                    PathTemplate.from(normalizedPath)
            );
        }
    }

    private record PathTemplate(
            @NotNull String normalizedPath,
            int variables,
            int wildcards,
            int staticSegments,
            @Nullable Pattern pattern
    ) {
        private static @NotNull PathTemplate from(@NotNull String normalizedPath) {
            String[] segments = normalizedPath.equals("/")
                    ? new String[0]
                    : normalizedPath.substring(1).split("/");
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
                return new PathTemplate(
                        normalizedPath,
                        variables,
                        wildcards,
                        staticSegments,
                        Pattern.compile(regex.toString())
                );
            } catch (PatternSyntaxException ignored) {
                return new PathTemplate(normalizedPath, variables, wildcards, staticSegments, null);
            }
        }

        private @NotNull MatchDetails matches(@NotNull String normalizedInput) {
            if (normalizedPath.equals(normalizedInput)) {
                return new MatchDetails(true, 1_000, 0, 0);
            }
            if (pattern == null || !pattern.matcher(normalizedInput).matches()) {
                return new MatchDetails(false, 0, variables, wildcards);
            }
            int score = 800 + (staticSegments * 20) - (variables * 8) - (wildcards * 45);
            return new MatchDetails(true, score, variables, wildcards);
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
