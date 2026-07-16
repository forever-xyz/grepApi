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
    public List<String> ignoredPrefixes = new ArrayList<>();
    public String matchHighlightPalette = "BLUE";

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

    @Override
    public @Nullable GrepApiSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull GrepApiSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
