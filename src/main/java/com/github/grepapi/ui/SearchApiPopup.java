package com.github.grepapi.ui;

import com.github.grepapi.core.RouteMatcher;
import com.github.grepapi.core.UrlInputParser;
import com.github.grepapi.model.ApiMatchResult;
import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.model.ApiRouteMatch;
import com.github.grepapi.model.ApiSearchRequest;
import com.github.grepapi.service.ApiRouteService;
import com.github.grepapi.settings.GrepApiSettings;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
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
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SearchApiPopup {
    private static final int MAX_RESULTS = 500;
    private static final int SEARCH_DELAY_MS = 80;
    private static final String ALL_METHODS = "全部方式";
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
        JPanel panel = createPanel();
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, searchField.getTextEditor())
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelKeyEnabled(true)
                .setDimensionServiceKey(project, "GrepApi.SearchPopup.v3", false)
                .createPopup();

        configureInteractions();
        updateResults();
        popup.showCenteredInCurrentWindow(project);
        loadRoutesInBackground();
    }

    private @NotNull JPanel createPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(400)));

        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(7)));
        JBLabel title = new JBLabel("搜索 API 接口", PLUGIN_ICON, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));

        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        methodFilter.setPreferredSize(new Dimension(JBUI.scale(104), JBUI.scale(32)));
        methodFilter.setToolTipText("按 HTTP 请求方式筛选");
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(methodFilter, BorderLayout.EAST);
        header.add(title, BorderLayout.NORTH);
        header.add(searchRow, BorderLayout.CENTER);

        searchField.getTextEditor().getEmptyText()
                .setText("输入 URL、路径、方法名、Controller 或模块名称");

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new RouteResultRenderer(
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
                if (event.getClickCount() == 2) {
                    openSelectedRoute();
                }
            }
        });
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
        String input = searchField.getText();
        ApiSearchRequest request = UrlInputParser.parse(
                input,
                GrepApiSettings.getInstance(project).getIgnoredPrefixes()
        );
        request = applyMethodFilter(request);

        ApiMatchResult result;
        if (request == null) {
            result = firstRoutes(routes, MAX_RESULTS);
        } else {
            result = matcher.search(request, routes, MAX_RESULTS);
        }

        listModel.clear();
        for (ApiRouteMatch match : result.matches()) {
            listModel.addElement(match);
        }
        if (!listModel.isEmpty()) {
            resultList.setSelectedIndex(0);
        }

        if (loading.get() && routes.isEmpty()) {
            statusLabel.setText("正在后台加载接口索引…");
        } else {
            statusLabel.setText(
                    "找到 " + result.totalMatches() + " 个结果，已索引 " + routes.size()
                            + " 个接口（最多显示 " + MAX_RESULTS + " 条）"
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

    private @NotNull ApiMatchResult firstRoutes(@NotNull List<ApiRoute> source, int limit) {
        int size = Math.min(source.size(), limit);
        java.util.ArrayList<ApiRouteMatch> matches = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ApiRoute route = source.get(index);
            matches.add(new ApiRouteMatch(route, 0, "", 0, "全部接口"));
        }
        return new ApiMatchResult(List.copyOf(matches), source.size());
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
                .coalesceBy(project, ApiRouteService.class)
                .expireWith(project)
                .expireWhen(() -> popup == null || popup.isDisposed())
                .finishOnUiThread(ModalityState.any(), refreshedRoutes -> {
                    routes = refreshedRoutes;
                    loading.set(false);
                    updateResults();
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void openSelectedRoute() {
        ApiRouteMatch selected = resultList.getSelectedValue();
        if (selected == null) {
            return;
        }
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

    private record NavigationTarget(@NotNull VirtualFile file, int startOffset, int endOffset) {
    }
}
