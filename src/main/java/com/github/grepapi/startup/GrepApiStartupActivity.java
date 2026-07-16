package com.github.grepapi.startup;

import com.github.grepapi.service.ApiRouteService;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

public final class GrepApiStartupActivity implements StartupActivity.Background {
    @Override
    public void runActivity(@NotNull Project project) {
        DumbService.getInstance(project).runWhenSmart(() -> ReadAction
                .nonBlocking(ApiRouteService.getInstance(project)::getRoutes)
                .coalesceBy(project, ApiRouteService.class)
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService()));
    }
}
