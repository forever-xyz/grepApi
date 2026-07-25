package com.github.grepapi.core;

import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.model.ApiRouteMatch;
import com.github.grepapi.model.ApiSearchRequest;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RouteMatcherTest {
    private final RouteMatcher matcher = new RouteMatcher();

    @Test
    void matchesConcreteUrlToPathVariable() {
        ApiRoute route = route(Set.of("GET"), "/api/users/{id}");
        ApiSearchRequest request = UrlInputParser.parse("GET /api/users/123", List.of());

        List<ApiRouteMatch> matches = matcher.match(request, List.of(route));

        assertFalse(matches.isEmpty());
        assertEquals("/api/users/{id}", matches.get(0).route().path());
    }

    @Test
    void rejectsWrongHttpMethod() {
        ApiRoute route = route(Set.of("POST"), "/api/users/{id}");
        ApiSearchRequest request = UrlInputParser.parse("GET /api/users/123", List.of());

        assertEquals(0, matcher.match(request, List.of(route)).size());
    }

    @Test
    void fuzzyMatchesPartialUrlAndMethodName() {
        ApiRoute route = route(Set.of("POST"), "/web/tradeShipLine/add", "addTradeShipLine");

        ApiSearchRequest pathRequest = UrlInputParser.parse("tradeShip", List.of());
        ApiSearchRequest methodRequest = UrlInputParser.parse("addTrade", List.of());

        assertEquals(route, matcher.match(pathRequest, List.of(route)).get(0).route());
        assertEquals(route, matcher.match(methodRequest, List.of(route)).get(0).route());
    }

    @Test
    void highlightsTheCompleteRelativePathMatch() {
        ApiRoute route = route(
                Set.of("GET"),
                "/web/trainResourcePool/queryByWholePlanNo"
        );
        ApiSearchRequest request = UrlInputParser.parse(
                "trainResourcePool/queryByWholePlanNo",
                List.of()
        );

        ApiRouteMatch match = matcher.match(request, List.of(route)).get(0);

        assertEquals(
                "/trainResourcePool/queryByWholePlanNo",
                match.matchedInputPath()
        );
    }

    @Test
    void supportsNonContiguousFuzzyCharacters() {
        ApiRoute route = route(Set.of("GET"), "/task/getNextNodeList", "getNextNodeList");
        ApiSearchRequest request = UrlInputParser.parse("gtnnl", List.of());

        assertFalse(matcher.match(request, List.of(route)).isEmpty());
    }

    @Test
    void exactPathRanksBeforeFuzzyResultsAndLimitIsApplied() {
        ApiRoute exact = route(Set.of("GET"), "/task/list", "list");
        ApiRoute fuzzy = route(Set.of("GET"), "/task/listAll", "listAll");
        ApiRoute another = route(Set.of("GET"), "/admin/task/list", "adminList");
        ApiSearchRequest request = UrlInputParser.parse("/task/list", List.of());

        var result = matcher.search(request, List.of(fuzzy, another, exact), 2);

        assertEquals(3, result.totalMatches());
        assertEquals(2, result.matches().size());
        assertEquals(exact, result.matches().get(0).route());
        assertTrue(result.matches().get(0).score() > result.matches().get(1).score());
    }

    @Test
    void pathInputOnlyMatchesContinuousPathText() {
        ApiRoute expected = route(Set.of("POST"), "/web/tradeShipLine/add", "pageList");
        ApiRoute expectedSecond = route(Set.of("POST"), "/web/tradeShipLine/edit", "edit");
        ApiRoute noisySubsequence = route(
                Set.of("POST"),
                "/web/excel/outTrade/shipLine/export",
                "exportPageShipLine"
        );
        ApiRoute noisyController = route(Set.of("POST"), "/web/other/export", "tradeShipLine");
        ApiSearchRequest request = UrlInputParser.parse("/web/tradeShipLine", List.of());

        var result = matcher.search(
                request,
                List.of(noisySubsequence, expectedSecond, noisyController, expected),
                100
        );

        assertEquals(2, result.totalMatches());
        assertEquals(List.of(expected, expectedSecond), result.matches().stream().map(ApiRouteMatch::route).toList());
    }

    @SuppressWarnings("unchecked")
    private ApiRoute route(Set<String> methods, String path) {
        return route(methods, path, "getUser");
    }

    @SuppressWarnings("unchecked")
    private ApiRoute route(Set<String> methods, String path, String methodName) {
        return new ApiRoute(
                methods,
                path,
                "example.UserController",
                methodName,
                null,
                (SmartPsiElementPointer<PsiElement>) mock(SmartPsiElementPointer.class)
        );
    }
}
