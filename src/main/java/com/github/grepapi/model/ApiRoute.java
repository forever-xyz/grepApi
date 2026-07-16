package com.github.grepapi.model;

import com.intellij.openapi.module.Module;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public record ApiRoute(
        @NotNull Set<String> httpMethods,
        @NotNull String path,
        @NotNull String className,
        @NotNull String methodName,
        @Nullable Module module,
        @NotNull SmartPsiElementPointer<PsiElement> navigationPointer
) {
    public @NotNull String displayHttpMethods() {
        return httpMethods.isEmpty() ? "ANY" : String.join(",", httpMethods);
    }

    public @NotNull String moduleName() {
        return module == null ? "project" : module.getName();
    }

    public @NotNull String simpleClassName() {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }
}
