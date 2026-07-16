package com.github.grepapi.model;

import org.jetbrains.annotations.NotNull;

public record ApiRouteMatch(
        @NotNull ApiRoute route,
        int score,
        @NotNull String matchedInputPath,
        int removedPrefixSegments,
        @NotNull String reason
) implements Comparable<ApiRouteMatch> {
    @Override
    public int compareTo(@NotNull ApiRouteMatch other) {
        int scoreComparison = Integer.compare(other.score, score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        return route.path().compareTo(other.route.path());
    }
}
