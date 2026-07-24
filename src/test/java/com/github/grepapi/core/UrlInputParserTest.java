package com.github.grepapi.core;

import com.github.grepapi.model.ApiSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UrlInputParserTest {
    @Test
    void parsesMethodAndFullUrl() {
        ApiSearchRequest request = UrlInputParser.parse(
                "GET https://api.example.com/gateway/user-service/api/users/123?detail=true",
                List.of("/gateway/user-service")
        );

        assertNotNull(request);
        assertEquals("GET", request.httpMethod());
        assertEquals("/gateway/user-service/api/users/123", request.path());
        assertEquals("/api/users/123", request.candidatePaths().get(1));
    }

    @Test
    void parsesCurlCommand() {
        ApiSearchRequest request = UrlInputParser.parse(
                "curl -X POST \"https://api.example.com/api/users\"",
                List.of()
        );

        assertNotNull(request);
        assertEquals("POST", request.httpMethod());
        assertEquals("/api/users", request.path());
    }

    @Test
    void acceptsPlainFuzzySearchText() {
        ApiSearchRequest request = UrlInputParser.parse("tradeShip", List.of());

        assertNotNull(request);
        assertNull(request.path());
        assertEquals("tradeShip", request.searchText());
        assertEquals(List.of(), request.candidatePaths());
    }

    @Test
    void parsesRelativePathFromItsFirstSegment() {
        ApiSearchRequest request = UrlInputParser.parse(
                "trainResourcePool/queryByWholePlanNo",
                List.of()
        );

        assertNotNull(request);
        assertEquals("/trainResourcePool/queryByWholePlanNo", request.path());
        assertEquals(
                List.of("/trainResourcePool/queryByWholePlanNo"),
                request.candidatePaths()
        );
    }
}
