package com.github.grepapi.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "GrepApiSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GrepApiSettings implements PersistentStateComponent<GrepApiSettings> {
    private static final int MAX_SAVED_SEARCH_LENGTH = 2_048;
    private static final int MAX_RECENT_ROUTES = 15;

    public List<String> ignoredPrefixes = new ArrayList<>();
    public String matchHighlightPalette = "BLUE";
    public String lastSearchText = "";
    public List<String> recentRouteKeys = new ArrayList<>();

    public static @NotNull GrepApiSettings getInstance(@NotNull Project project) {
        return project.getService(GrepApiSettings.class);
    }

    public @NotNull List<String> getIgnoredPrefixes() {
        return List.copyOf(ignoredPrefixes);
    }

    public void setIgnoredPrefixes(@NotNull List<String> prefixes) {
        ignoredPrefixes = new ArrayList<>(prefixes);
    }

    public @NotNull String getMatchHighlightPalette() {
        return matchHighlightPalette == null ? "BLUE" : matchHighlightPalette;
    }

    public void setMatchHighlightPalette(@NotNull String palette) {
        matchHighlightPalette = palette;
    }

    public @NotNull String getLastSearchText() {
        return lastSearchText == null ? "" : lastSearchText;
    }

    public void setLastSearchText(@NotNull String value) {
        lastSearchText = value.length() <= MAX_SAVED_SEARCH_LENGTH
                ? value
                : value.substring(0, MAX_SAVED_SEARCH_LENGTH);
    }

    public @NotNull List<String> getRecentRouteKeys() {
        return List.copyOf(recentRouteKeys);
    }

    public void recordRecentRoute(@NotNull String routeKey) {
        recentRouteKeys.remove(routeKey);
        recentRouteKeys.add(0, routeKey);
        if (recentRouteKeys.size() > MAX_RECENT_ROUTES) {
            recentRouteKeys = new ArrayList<>(recentRouteKeys.subList(0, MAX_RECENT_ROUTES));
        }
    }

    @Override
    public @Nullable GrepApiSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull GrepApiSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
