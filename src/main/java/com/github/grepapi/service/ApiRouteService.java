package com.github.grepapi.service;

import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.spring.SpringRouteExtractor;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class ApiRouteService {
    private final Project project;
    private final Map<VirtualFile, FileRoutes> fileCache = new HashMap<>();
    private volatile List<ApiRoute> cachedRoutes = List.of();
    private final Set<VirtualFile> dirtyFiles = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized;
    private long lastModificationCount = -1;

    public ApiRouteService(@NotNull Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                Document document = event.getDocument();
                VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                if (file != null && isSourceJavaFile(file)) {
                    dirtyFiles.add(file);
                }
            }
        }, project);
    }

    public static @NotNull ApiRouteService getInstance(@NotNull Project project) {
        return project.getService(ApiRouteService.class);
    }

    public @NotNull List<ApiRoute> getCachedRoutes() {
        return cachedRoutes;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Returns whether the cached routes still reflect the current PSI state. */
    public synchronized boolean isUpToDate() {
        return initialized
                && dirtyFiles.isEmpty()
                && PsiModificationTracker.getInstance(project).getModificationCount() == lastModificationCount;
    }

    public synchronized @NotNull List<ApiRoute> getRoutes() {
        long modificationCount = PsiModificationTracker.getInstance(project).getModificationCount();
        if (initialized && dirtyFiles.isEmpty() && modificationCount == lastModificationCount) {
            return cachedRoutes;
        }

        if (initialized && !dirtyFiles.isEmpty()) {
            updateDirtyFiles();
            lastModificationCount = modificationCount;
            return cachedRoutes;
        }

        List<ApiRoute> routes = new ArrayList<>();
        Map<VirtualFile, FileRoutes> updatedCache = new HashMap<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        SpringRouteExtractor extractor = new SpringRouteExtractor(project);

        for (var virtualFile : FileTypeIndex.getFiles(
                JavaFileType.INSTANCE,
                GlobalSearchScope.projectScope(project)
        )) {
            if (!isSourceJavaFile(virtualFile, fileIndex)) {
                continue;
            }
            if (psiManager.findFile(virtualFile) instanceof PsiJavaFile javaFile) {
                long modificationStamp = javaFile.getModificationStamp();
                FileRoutes previous = fileCache.get(virtualFile);
                FileRoutes current;
                if (previous != null && previous.modificationStamp() == modificationStamp) {
                    current = previous;
                } else {
                    current = new FileRoutes(modificationStamp, List.copyOf(extractor.extract(javaFile)));
                }
                updatedCache.put(virtualFile, current);
                routes.addAll(current.routes());
            }
        }
        fileCache.clear();
        fileCache.putAll(updatedCache);
        cachedRoutes = List.copyOf(routes);
        initialized = true;
        dirtyFiles.clear();
        lastModificationCount = modificationCount;
        return cachedRoutes;
    }

    public synchronized void invalidate() {
        fileCache.clear();
        cachedRoutes = List.of();
        dirtyFiles.clear();
        initialized = false;
        lastModificationCount = -1;
    }

    /** Re-extracts only edited Java files after the initial project scan. */
    private void updateDirtyFiles() {
        Set<VirtualFile> filesToUpdate = Set.copyOf(dirtyFiles);
        dirtyFiles.removeAll(filesToUpdate);
        PsiManager psiManager = PsiManager.getInstance(project);
        SpringRouteExtractor extractor = new SpringRouteExtractor(project);

        for (VirtualFile file : filesToUpdate) {
            if (!isSourceJavaFile(file)) {
                fileCache.remove(file);
                continue;
            }
            if (psiManager.findFile(file) instanceof PsiJavaFile javaFile) {
                fileCache.put(file, new FileRoutes(
                        javaFile.getModificationStamp(),
                        List.copyOf(extractor.extract(javaFile))
                ));
            } else {
                fileCache.remove(file);
            }
        }

        List<ApiRoute> routes = new ArrayList<>();
        for (FileRoutes fileRoutes : fileCache.values()) {
            routes.addAll(fileRoutes.routes());
        }
        cachedRoutes = List.copyOf(routes);
    }

    private boolean isSourceJavaFile(@NotNull VirtualFile file) {
        return file.getFileType() == JavaFileType.INSTANCE
                && isSourceJavaFile(file, ProjectFileIndex.getInstance(project));
    }

    private boolean isSourceJavaFile(@NotNull VirtualFile file, @NotNull ProjectFileIndex fileIndex) {
        return fileIndex.isInSourceContent(file) && !fileIndex.isInTestSourceContent(file);
    }

    private record FileRoutes(long modificationStamp, @NotNull List<ApiRoute> routes) {
    }
}
