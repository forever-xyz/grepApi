package com.github.grepapi.settings;

import com.github.grepapi.ui.MatchHighlightPalette;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.Arrays;
import java.util.List;

public final class GrepApiConfigurable implements Configurable {
    private final Project project;
    private JBTextArea prefixesField;
    private ComboBox<MatchHighlightPalette> highlightPaletteField;
    private JBLabel highlightPreview;
    private JPanel panel;

    public GrepApiConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Grep API";
    }

    @Override
    public @Nullable JComponent createComponent() {
        prefixesField = new JBTextArea(8, 50);
        prefixesField.setLineWrap(false);
        highlightPaletteField = new ComboBox<>(MatchHighlightPalette.values());
        highlightPreview = new JBLabel("confirm");
        highlightPreview.setOpaque(true);
        highlightPreview.setBorder(com.intellij.util.ui.JBUI.Borders.empty(3, 8));
        highlightPaletteField.addActionListener(event -> updateHighlightPreview());

        JPanel previewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        previewPanel.add(highlightPreview);
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("匹配文字背景色：", highlightPaletteField)
                .addLabeledComponent("高亮预览：", previewPanel)
                .addSeparator()
                .addComponent(new JBLabel("搜索前需要移除的网关或服务前缀（每行一个）："))
                .addComponent(new JBScrollPane(prefixesField))
                .addComponent(new JBLabel("示例：/gateway/user-service"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        GrepApiSettings settings = GrepApiSettings.getInstance(project);
        return !readPrefixes().equals(settings.getIgnoredPrefixes())
                || selectedPalette() != MatchHighlightPalette.fromId(settings.getMatchHighlightPalette());
    }

    @Override
    public void apply() {
        GrepApiSettings settings = GrepApiSettings.getInstance(project);
        settings.setIgnoredPrefixes(readPrefixes());
        settings.setMatchHighlightPalette(selectedPalette().name());
    }

    @Override
    public void reset() {
        if (prefixesField != null) {
            GrepApiSettings settings = GrepApiSettings.getInstance(project);
            prefixesField.setText(String.join("\n", settings.getIgnoredPrefixes()));
            highlightPaletteField.setSelectedItem(
                    MatchHighlightPalette.fromId(settings.getMatchHighlightPalette())
            );
            updateHighlightPreview();
        }
    }

    @Override
    public void disposeUIResources() {
        prefixesField = null;
        highlightPaletteField = null;
        highlightPreview = null;
        panel = null;
    }

    private @NotNull MatchHighlightPalette selectedPalette() {
        if (highlightPaletteField != null
                && highlightPaletteField.getSelectedItem() instanceof MatchHighlightPalette palette) {
            return palette;
        }
        return MatchHighlightPalette.BLUE;
    }

    private void updateHighlightPreview() {
        if (highlightPreview == null) {
            return;
        }
        MatchHighlightPalette palette = selectedPalette();
        highlightPreview.setBackground(palette.background());
        highlightPreview.setForeground(palette.foreground());
    }

    private @NotNull List<String> readPrefixes() {
        if (prefixesField == null) {
            return List.of();
        }
        return Arrays.stream(prefixesField.getText().split("\\R"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
