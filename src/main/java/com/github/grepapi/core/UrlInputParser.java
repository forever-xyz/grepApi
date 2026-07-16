package com.github.grepapi.core;

import com.github.grepapi.model.ApiSearchRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlInputParser {
    private static final Pattern HTTP_METHOD = Pattern.compile(
            "(?i)(?:^|\\s|(?:-X\\s+))(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)(?=\\s|$)"
    );
    private static final Pattern FULL_URL = Pattern.compile("https?://[^\\s\\\"']+");
    private static final Pattern PATH = Pattern.compile("/[^\\s\\\"']*");

    private UrlInputParser() {
    }

    public static @Nullable ApiSearchRequest parse(@Nullable String input, @NotNull List<String> ignoredPrefixes) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String text = input.trim();
        String method = extractMethod(text);
        String rawPath = extractPath(text);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalizedPath = null;
        String searchText;

        if (rawPath == null || rawPath.isBlank()) {
            searchText = removeLeadingHttpMethod(text).trim();
        } else {
            normalizedPath = normalizePath(rawPath);
            searchText = normalizedPath;
            candidates.add(normalizedPath);

            for (String prefix : ignoredPrefixes) {
                String normalizedPrefix = normalizePath(prefix);
                if (!"/".equals(normalizedPrefix) && startsWithPathPrefix(normalizedPath, normalizedPrefix)) {
                    String stripped = normalizedPath.substring(normalizedPrefix.length());
                    candidates.add(normalizePath(stripped));
                }
            }
        }

        if (searchText.isBlank() && method == null) {
            return null;
        }
        return new ApiSearchRequest(method, text, searchText, normalizedPath, List.copyOf(candidates));
    }

    private static @Nullable String extractMethod(@NotNull String text) {
        Matcher matcher = HTTP_METHOD.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static @NotNull String removeLeadingHttpMethod(@NotNull String text) {
        return text.replaceFirst(
                "(?i)^\\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s*",
                ""
        );
    }

    private static @Nullable String extractPath(@NotNull String text) {
        Matcher urlMatcher = FULL_URL.matcher(text);
        if (urlMatcher.find()) {
            String url = trimTrailingPunctuation(urlMatcher.group());
            try {
                URI uri = new URI(url);
                return uri.getRawPath();
            } catch (URISyntaxException ignored) {
                int scheme = url.indexOf("://");
                int slash = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
                return slash < 0 ? "/" : url.substring(slash);
            }
        }

        Matcher pathMatcher = PATH.matcher(text);
        if (pathMatcher.find()) {
            return trimTrailingPunctuation(pathMatcher.group());
        }
        return null;
    }

    private static @NotNull String trimTrailingPunctuation(@NotNull String value) {
        int end = value.length();
        while (end > 0 && ").,;]`".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    public static @NotNull String normalizePath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String result = path.trim().replace('\\', '/');
        int query = result.indexOf('?');
        if (query >= 0) {
            result = result.substring(0, query);
        }
        int fragment = result.indexOf('#');
        if (fragment >= 0) {
            result = result.substring(0, fragment);
        }
        result = result.replaceAll("/{2,}", "/");
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean startsWithPathPrefix(@NotNull String path, @NotNull String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

}
