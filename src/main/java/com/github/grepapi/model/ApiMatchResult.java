package com.github.grepapi.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ApiMatchResult(
        @NotNull List<ApiRouteMatch> matches,
        int totalMatches
) {
}
