package com.github.grepapi.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

public enum MatchHighlightPalette {
    BLUE("蓝色（推荐）", 0xDCEBFF, 0x315A9B, 0x174EA6, 0xFFFFFF),
    GREEN("绿色", 0xD9F3E5, 0x286347, 0x17613B, 0xFFFFFF),
    ORANGE("橙色", 0xFFE4B5, 0x7A5420, 0x8A4B00, 0xFFFFFF),
    PURPLE("紫色", 0xEADFFF, 0x5A4387, 0x5A2EA6, 0xFFFFFF);

    private final String displayName;
    private final JBColor background;
    private final JBColor foreground;

    MatchHighlightPalette(
            @NotNull String displayName,
            int lightBackground,
            int darkBackground,
            int lightForeground,
            int darkForeground
    ) {
        this.displayName = displayName;
        background = new JBColor(lightBackground, darkBackground);
        foreground = new JBColor(lightForeground, darkForeground);
    }

    public static @NotNull MatchHighlightPalette fromId(String id) {
        if (id != null) {
            for (MatchHighlightPalette palette : values()) {
                if (palette.name().equalsIgnoreCase(id)) {
                    return palette;
                }
            }
        }
        return BLUE;
    }

    public @NotNull SimpleTextAttributes textAttributes() {
        return new SimpleTextAttributes(
                background,
                foreground,
                null,
                SimpleTextAttributes.STYLE_BOLD
        );
    }

    public @NotNull Color background() {
        return background;
    }

    public @NotNull Color foreground() {
        return foreground;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
