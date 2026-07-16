package com.github.grepapi.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ApiSearchRequest(
        @Nullable String httpMethod,
        @NotNull String originalText,
        @NotNull String searchText,
        @Nullable String path,
        @NotNull List<String> candidatePaths
) {
}
