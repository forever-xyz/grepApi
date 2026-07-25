package com.github.grepapi.ui;

import com.github.grepapi.core.RouteMatcher;
import com.github.grepapi.core.UrlInputParser;
import com.github.grepapi.model.ApiMatchResult;
import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.model.ApiRouteMatch;
import com.github.grepapi.model.ApiSearchRequest;
import com.github.grepapi.service.ApiRouteService;
import com.github.grepapi.settings.GrepApiSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SearchApiPopup {
    private static final int MAX_RESULTS = 50;
    private static final int SEARCH_DELAY_MS = 150;
    private static final String ALL_METHODS = "全部";
    private static final String[] HTTP_METHOD_FILTERS = {
            ALL_METHODS, "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"
    };
    private static final Icon PLUGIN_ICON = IconLoader.getIcon(
            "/icons/grepApi.svg",
            SearchApiPopup.class
    );

    private final Project project;
    private final ApiRouteService routeService;
    private final RouteMatcher matcher = new RouteMatcher();
    private final DefaultListModel<ApiRouteMatch> listModel = new DefaultListModel<>();
    private final JBList<ApiRouteMatch> resultList = new JBList<>(listModel);
    private final SearchTextField searchField = new SearchTextField(false);
    private final ComboBox<String> methodFilter = new ComboBox<>(HTTP_METHOD_FILTERS);
    private final JBLabel statusLabel = new JBLabel(" ");
    private final Timer searchTimer;
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicLong searchGeneration = new AtomicLong();
    private final AtomicReference<ProgressIndicator> activeSearch = new AtomicReference<>();

    private volatile List<ApiRoute> routes;
    private JBPopup popup;

    public SearchApiPopup(@NotNull Project project) {
        this.project = project;
        routeService = ApiRouteService.getInstance(project);
        routes = routeService.getCachedRoutes();
        searchTimer = new Timer(SEARCH_DELAY_MS, event -> updateResults());
        searchTimer.setRepeats(false);
    }

    public void show() {
        // Include newly typed controller methods without forcing the user to save files.
        commitPendingSourceDocuments();
        JPanel panel = createPanel();
        restoreLastSearch();
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, searchField.getTextEditor())
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelKeyEnabled(true)
                .setDimensionServiceKey(project, "GrepApi.SearchPopup.v4", false)
                .createPopup();
        popup.addListener(new JBPopupListener() {
            @Override
            public void onClosed(@NotNull LightweightWindowEvent event) {
                cancelActiveSearch();
            }
        });

        configureInteractions();
        updateResults();
        popup.showCenteredInCurrentWindow(project);
        searchField.getTextEditor().selectAll();
        loadRoutesInBackground();
    }

    private @NotNull JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(340)));

        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(7)));
        JBLabel title = new JBLabel("搜索 API 接口", PLUGIN_ICON, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));

        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        methodFilter.setPreferredSize(new Dimension(JBUI.scale(88), JBUI.scale(30)));
        methodFilter.setToolTipText("按 HTTP 请求方式筛选");
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(methodFilter, BorderLayout.EAST);
        header.add(title, BorderLayout.NORTH);
        header.add(searchRow, BorderLayout.CENTER);

        searchField.getTextEditor().getEmptyText()
                .setText("输入 URL、路径、方法名、Controller 或模块名称");

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new RoundedRouteResultRenderer(
                MatchHighlightPalette.fromId(
                        GrepApiSettings.getInstance(project).getMatchHighlightPalette()
                )
        ));
        resultList.setEmptyText("没有找到匹配的 API 接口");

        JBScrollPane scrollPane = new JBScrollPane(resultList);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        statusLabel.setForeground(JBColor.GRAY);

        panel.add(header, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private void configureInteractions() {
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                GrepApiSettings.getInstance(project).setLastSearchText(searchField.getText());
                searchGeneration.incrementAndGet();
                searchTimer.restart();
            }
        });
        methodFilter.addActionListener(event -> updateResults());

        JComponent editor = searchField.getTextEditor();
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "grepApi.next");
        editor.getActionMap().put("grepApi.next", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(1);
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "grepApi.previous");
        editor.getActionMap().put("grepApi.previous", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(-1);
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "grepApi.open");
        editor.getActionMap().put("grepApi.open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelectedRoute();
            }
        });

        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = resultList.locationToIndex(event.getPoint());
                if (index >= 0
                        && SwingUtilities.isLeftMouseButton(event)
                        && resultList.getCellBounds(index, index).contains(event.getPoint())) {
                    resultList.setSelectedIndex(index);
                    openSelectedRoute();
                }
            }
        });
    }

    private void restoreLastSearch() {
        String lastSearch = GrepApiSettings.getInstance(project).getLastSearchText();
        searchField.setText(lastSearch);
        searchField.getTextEditor().selectAll();
    }

    /** Commits only changed project Java files, never every open editor document. */
    private void commitPendingSourceDocuments() {
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        if (!documentManager.hasUncommitedDocuments()) {
            return;
        }
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        for (Document document : documentManager.getUncommittedDocuments()) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);
            if (file != null
                    && "java".equalsIgnoreCase(file.getExtension())
                    && fileIndex.isInSourceContent(file)
                    && !fileIndex.isInTestSourceContent(file)) {
                documentManager.commitDocument(document);
            }
        }
    }

    private void moveSelection(int direction) {
        if (listModel.isEmpty()) {
            return;
        }
        int current = Math.max(0, resultList.getSelectedIndex());
        int next = Math.max(0, Math.min(listModel.size() - 1, current + direction));
        resultList.setSelectedIndex(next);
        resultList.ensureIndexIsVisible(next);
    }

    private void updateResults() {
        if (!routeService.isUpToDate()) {
            loadRoutesInBackground();
        }
        cancelActiveSearch();
        long generation = searchGeneration.incrementAndGet();
        String input = searchField.getText();
        ApiSearchRequest request = UrlInputParser.parse(
                input,
                GrepApiSettings.getInstance(project).getIgnoredPrefixes()
        );
        request = applyMethodFilter(request);
        List<ApiRoute> routeSnapshot = routes;

        if (request == null) {
            applyResults(
                    generation,
                    recentRoutes(routeSnapshot),
                    routeSnapshot.size(),
                    GrepApiSettings.MAX_RECENT_ROUTES
            );
            return;
        }

        statusLabel.setText("正在搜索…");
        ApiSearchRequest searchRequest = request;
        ProgressIndicator indicator = new EmptyProgressIndicator();
        activeSearch.set(indicator);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ApiMatchResult result = ProgressManager.getInstance().runProcess(
                        () -> matcher.search(searchRequest, routeSnapshot, MAX_RESULTS),
                        indicator
                );
                ApplicationManager.getApplication().invokeLater(
                        () -> applyResults(generation, result, routeSnapshot.size(), MAX_RESULTS),
                        ModalityState.any()
                );
            } catch (ProcessCanceledException ignored) {
                // A newer query or a closed popup has superseded this result.
            } finally {
                activeSearch.compareAndSet(indicator, null);
            }
        });
    }

    private void cancelActiveSearch() {
        ProgressIndicator previous = activeSearch.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void applyResults(long generation, @NotNull ApiMatchResult result, int routeCount, int limit) {
        if (generation != searchGeneration.get() || popup == null || popup.isDisposed()) {
            return;
        }
        listModel.clear();
        listModel.addAll(result.matches());
        if (!listModel.isEmpty()) {
            resultList.setSelectedIndex(0);
            resultList.ensureIndexIsVisible(0);
        }
        if (loading.get() && routeCount == 0) {
            statusLabel.setText("正在后台加载接口索引…");
        } else {
            statusLabel.setText(
                    "找到 " + result.totalMatches() + " 个结果，已索引 " + routeCount
                            + " 个接口（最多显示 " + limit + " 条）"
            );
        }
    }

    private ApiSearchRequest applyMethodFilter(ApiSearchRequest request) {
        Object selected = methodFilter.getSelectedItem();
        if (!(selected instanceof String method) || ALL_METHODS.equals(method)) {
            return request;
        }
        if (request == null) {
            return new ApiSearchRequest(method, "", "", null, List.of());
        }
        return new ApiSearchRequest(
                method,
                request.originalText(),
                request.searchText(),
                request.path(),
                request.candidatePaths()
        );
    }

    private @NotNull ApiMatchResult recentRoutes(@NotNull List<ApiRoute> source) {
        List<String> recentKeys = GrepApiSettings.getInstance(project).getRecentRouteKeys();
        if (recentKeys.isEmpty()) {
            return new ApiMatchResult(List.of(), 0);
        }

        Map<String, ApiRoute> routesByKey = new HashMap<>();
        for (ApiRoute route : source) {
            routesByKey.put(routeKey(route), route);
        }
        java.util.ArrayList<ApiRouteMatch> matches = new java.util.ArrayList<>(recentKeys.size());
        for (String key : recentKeys) {
            ApiRoute route = routesByKey.get(key);
            if (route != null) {
                matches.add(new ApiRouteMatch(route, 0, "", 0, "最近打开"));
            }
        }
        return new ApiMatchResult(List.copyOf(matches), matches.size());
    }

    private void loadRoutesInBackground() {
        if (popup == null || popup.isDisposed() || !loading.compareAndSet(false, true)) {
            return;
        }
        if (DumbService.getInstance(project).isDumb()) {
            statusLabel.setText(routes.isEmpty() ? "IDEA 正在建立项目索引…" : "正在使用已有接口索引…");
            loading.set(false);
            DumbService.getInstance(project).runWhenSmart(() -> {
                if (popup != null && !popup.isDisposed()) {
                    loadRoutesInBackground();
                }
            });
            return;
        }

        ReadAction.nonBlocking(routeService::getRoutes)
                .coalesceBy(project, SearchApiPopup.class)
                .expireWith(project)
                .expireWhen(() -> popup == null || popup.isDisposed())
                .finishOnUiThread(ModalityState.any(), refreshedRoutes -> {
                    routes = refreshedRoutes;
                    loading.set(false);
                    matcher.clearCache();
                    updateResults();
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void openSelectedRoute() {
        ApiRouteMatch selected = resultList.getSelectedValue();
        if (selected == null) {
            return;
        }
        GrepApiSettings.getInstance(project).recordRecentRoute(routeKey(selected.route()));
        navigate(selected.route());
        if (popup != null) {
            popup.cancel();
        }
    }

    private void navigate(@NotNull ApiRoute route) {
        NavigationTarget target = ReadAction.compute(() -> {
            PsiElement element = route.navigationPointer().getElement();
            if (element == null || !element.isValid() || element.getContainingFile() == null) {
                return null;
            }
            VirtualFile file = element.getContainingFile().getVirtualFile();
            if (file == null) {
                return null;
            }
            return new NavigationTarget(file, element.getTextOffset(), element.getTextRange().getEndOffset());
        });
        if (target == null) {
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).openTextEditor(
                new OpenFileDescriptor(project, target.file(), target.startOffset()),
                true
        );
        if (editor != null) {
            editor.getCaretModel().moveToOffset(target.startOffset());
            editor.getSelectionModel().setSelection(target.startOffset(), target.endOffset());
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        }
    }

    private static final class RouteResultRenderer extends ColoredListCellRenderer<ApiRouteMatch> {
        private final SimpleTextAttributes matchAttributes;

        private RouteResultRenderer(@NotNull MatchHighlightPalette palette) {
            matchAttributes = palette.textAttributes();
        }

        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends ApiRouteMatch> list,
                ApiRouteMatch value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            ApiRoute route = value.route();
            setIcon(HttpMethodIcons.forRoute(route));
            setIconTextGap(JBUI.scale(6));
            append(route.displayHttpMethods() + "  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            appendPath(route.path(), value.matchedInputPath());
            append("    Java：(" + route.simpleClassName() + "#" + route.methodName() + ")",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append("    模块：" + route.moduleName(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            append("    " + value.reason(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            setToolTipText(route.className() + "#" + route.methodName());
            setBorder(JBUI.Borders.empty(5, 4));
        }

        private void appendPath(@NotNull String path, @NotNull String query) {
            String normalizedQuery = query.trim();
            int methodSeparator = normalizedQuery.indexOf(' ');
            if (methodSeparator > 0) {
                normalizedQuery = normalizedQuery.substring(methodSeparator + 1).trim();
            }
            if (normalizedQuery.isEmpty()) {
                append(path, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                return;
            }

            String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
            String lowerQuery = normalizedQuery.toLowerCase(java.util.Locale.ROOT);
            int cursor = 0;
            int matchIndex = lowerPath.indexOf(lowerQuery);
            if (matchIndex < 0) {
                append(path, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                return;
            }

            while (matchIndex >= 0) {
                append(path.substring(cursor, matchIndex), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                int matchEnd = matchIndex + normalizedQuery.length();
                append(path.substring(matchIndex, matchEnd), matchAttributes);
                cursor = matchEnd;
                matchIndex = lowerPath.indexOf(lowerQuery, cursor);
            }
            append(path.substring(cursor), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }
    }

    private static @NotNull String routeKey(@NotNull ApiRoute route) {
        return route.displayHttpMethods() + "|" + route.path() + "|"
                + route.className() + "#" + route.methodName();
    }

    /** Compact renderer that keeps the matched URL in a visually distinct rounded chip. */
    private static final class RoundedRouteResultRenderer extends JPanel
            implements ListCellRenderer<ApiRouteMatch> {
        private final JLabel methodLabel = new JLabel();
        private final RoundedPathLabel pathLabel;
        private final JLabel targetLabel = new JLabel();
        private final JPanel routePanel = new JPanel();

        private RoundedRouteResultRenderer(@NotNull MatchHighlightPalette palette) {
            setLayout(new BorderLayout(JBUI.scale(10), 0));
            setBorder(JBUI.Borders.empty(4, 6));

            methodLabel.setFont(methodLabel.getFont().deriveFont(Font.BOLD));
            pathLabel = new RoundedPathLabel(palette);
            routePanel.setOpaque(false);
            routePanel.setLayout(new BoxLayout(routePanel, BoxLayout.X_AXIS));
            routePanel.add(methodLabel);
            routePanel.add(Box.createHorizontalStrut(JBUI.scale(8)));
            routePanel.add(pathLabel);
            targetLabel.setPreferredSize(new Dimension(JBUI.scale(190), 0));
            add(routePanel, BorderLayout.CENTER);
            add(targetLabel, BorderLayout.EAST);
        }

        @Override
        public JComponent getListCellRendererComponent(
                JList<? extends ApiRouteMatch> list,
                ApiRouteMatch value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            ApiRoute route = value.route();
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            Color secondary = selected ? foreground : JBColor.GRAY;

            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            methodLabel.setIcon(HttpMethodIcons.forRoute(route));
            methodLabel.setIconTextGap(JBUI.scale(5));
            methodLabel.setText(route.displayHttpMethods());
            methodLabel.setForeground(foreground);
            pathLabel.configure(route.path(), value.matchedInputPath(), selected, foreground);
            targetLabel.setText("Java: " + route.simpleClassName() + "#" + route.methodName());
            targetLabel.setForeground(secondary);
            setToolTipText(route.path() + "\n" + route.className() + "#" + route.methodName());
            return this;
        }
    }

    /** Draws URL text with a rounded background and a bold foreground for matched fragments. */
    private static final class RoundedPathLabel extends JComponent {
        private final MatchHighlightPalette palette;
        private String path = "";
        private String query = "";
        private boolean selected;
        private Color regularForeground = JBColor.foreground();

        private RoundedPathLabel(@NotNull MatchHighlightPalette palette) {
            this.palette = palette;
            setOpaque(false);
            setFont(displayFont().deriveFont(Font.BOLD));
        }

        private void configure(
                @NotNull String path,
                @NotNull String query,
                boolean selected,
                @NotNull Color regularForeground
        ) {
            this.path = path;
            this.query = normalizeQuery(query);
            this.selected = selected;
            this.regularForeground = regularForeground;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            int horizontalPadding = JBUI.scale(8);
            Font font = displayFont();
            int height = Math.max(JBUI.scale(24), getFontMetrics(font).getHeight() + JBUI.scale(8));
            return new Dimension(getFontMetrics(font).stringWidth(path) + horizontalPadding * 2, height);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int height = getHeight();
                int horizontalPadding = JBUI.scale(8);
                Font regularFont = displayFont();
                Font matchFont = regularFont.deriveFont(Font.BOLD);
                int baseline = (height - getFontMetrics(regularFont).getHeight()) / 2
                        + getFontMetrics(regularFont).getAscent();
                drawHighlightedPath(g, horizontalPadding, baseline, height, regularFont, matchFont);
            } finally {
                g.dispose();
            }
        }

        private void drawHighlightedPath(
                @NotNull Graphics2D g,
                int x,
                int baseline,
                int height,
                @NotNull Font regularFont,
                @NotNull Font matchFont
        ) {
            if (query.isEmpty()) {
                g.setFont(regularFont);
                g.setColor(regularForeground);
                g.drawString(path, x, baseline);
                return;
            }

            String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
            String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
            int cursor = 0;
            int matchIndex = lowerPath.indexOf(lowerQuery);
            while (matchIndex >= 0) {
                x = drawText(g, path.substring(cursor, matchIndex), x, baseline, regularFont, regularForeground);
                Color matchColor = selected ? regularForeground : palette.foreground();
                x = drawMatchedText(
                        g,
                        path.substring(matchIndex, matchIndex + query.length()),
                        x,
                        baseline,
                        height,
                        matchFont,
                        matchColor
                );
                cursor = matchIndex + query.length();
                matchIndex = lowerPath.indexOf(lowerQuery, cursor);
            }
            drawText(g, path.substring(cursor), x, baseline, regularFont, regularForeground);
        }

        private static int drawText(
                @NotNull Graphics2D g,
                @NotNull String text,
                int x,
                int baseline,
                @NotNull Font font,
                @NotNull Color color
        ) {
            g.setFont(font);
            g.setColor(color);
            g.drawString(text, x, baseline);
            return x + g.getFontMetrics(font).stringWidth(text);
        }

        private int drawMatchedText(
                @NotNull Graphics2D g,
                @NotNull String text,
                int x,
                int baseline,
                int height,
                @NotNull Font font,
                @NotNull Color color
        ) {
            int textWidth = g.getFontMetrics(font).stringWidth(text);
            int padding = JBUI.scale(3);
            Color background = palette.background();
            g.setColor(new Color(
                    background.getRed(),
                    background.getGreen(),
                    background.getBlue(),
                    selected ? 120 : 92
            ));
            g.fillRoundRect(
                    x - padding,
                    JBUI.scale(2),
                    textWidth + padding * 2,
                    Math.max(0, height - JBUI.scale(4)),
                    JBUI.scale(8),
                    JBUI.scale(8)
            );
            return drawText(g, text, x, baseline, font, color);
        }

        private static @NotNull String normalizeQuery(@NotNull String query) {
            String normalized = query.trim();
            int methodSeparator = normalized.indexOf(' ');
            return methodSeparator > 0 ? normalized.substring(methodSeparator + 1).trim() : normalized;
        }

        private @NotNull Font displayFont() {
            Font font = getFont();
            if (font != null) {
                return font;
            }
            Font labelFont = UIManager.getFont("Label.font");
            return labelFont != null ? labelFont : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    private record NavigationTarget(@NotNull VirtualFile file, int startOffset, int endOffset) {
    }
}
