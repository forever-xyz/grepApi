package com.github.grepapi.service;

import com.github.grepapi.model.ApiRoute;
import com.github.grepapi.spring.SpringRouteExtractor;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.components.Service;
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

@Service(Service.Level.PROJECT)
public final class ApiRouteService {
    private final Project project;
    private final Map<VirtualFile, FileRoutes> fileCache = new HashMap<>();
    private volatile List<ApiRoute> cachedRoutes = List.of();
    private volatile boolean initialized;
    private long lastModificationCount = -1;

    public ApiRouteService(@NotNull Project project) {
        this.project = project;
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

    public synchronized @NotNull List<ApiRoute> getRoutes() {
        long modificationCount = PsiModificationTracker.getInstance(project).getModificationCount();
        if (initialized && modificationCount == lastModificationCount) {
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
            if (!fileIndex.isInSourceContent(virtualFile) || fileIndex.isInTestSourceContent(virtualFile)) {
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
        lastModificationCount = modificationCount;
        return cachedRoutes;
    }

    public synchronized void invalidate() {
        fileCache.clear();
        cachedRoutes = List.of();
        initialized = false;
        lastModificationCount = -1;
    }

    private record FileRoutes(long modificationStamp, @NotNull List<ApiRoute> routes) {
    }
}
