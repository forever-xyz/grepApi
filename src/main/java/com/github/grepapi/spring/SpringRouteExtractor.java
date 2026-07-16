package com.github.grepapi.spring;

import com.github.grepapi.core.UrlInputParser;
import com.github.grepapi.model.ApiRoute;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SpringRouteExtractor {
    private static final String PACKAGE = "org.springframework.web.bind.annotation.";
    private static final String REQUEST_MAPPING = PACKAGE + "RequestMapping";
    private static final Map<String, String> METHOD_ANNOTATIONS = Map.of(
            PACKAGE + "GetMapping", "GET",
            PACKAGE + "PostMapping", "POST",
            PACKAGE + "PutMapping", "PUT",
            PACKAGE + "DeleteMapping", "DELETE",
            PACKAGE + "PatchMapping", "PATCH"
    );

    private final Project project;

    public SpringRouteExtractor(@NotNull Project project) {
        this.project = project;
    }

    public @NotNull List<ApiRoute> extract(@NotNull PsiJavaFile file) {
        List<ApiRoute> result = new ArrayList<>();
        for (PsiClass psiClass : PsiTreeUtil.findChildrenOfType(file, PsiClass.class)) {
            result.addAll(extractClass(psiClass));
        }
        return result;
    }

    private @NotNull List<ApiRoute> extractClass(@NotNull PsiClass psiClass) {
        List<ApiRoute> routes = new ArrayList<>();
        List<PathValue> classPaths = extractClassPaths(psiClass);
        String className = psiClass.getQualifiedName() == null
                ? safeName(psiClass.getName(), "AnonymousController")
                : psiClass.getQualifiedName();

        for (PsiMethod method : psiClass.getMethods()) {
            PsiModifierList modifierList = method.getModifierList();
            for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                String qualifiedName = resolveAnnotationName(annotation);
                if (!METHOD_ANNOTATIONS.containsKey(qualifiedName) && !REQUEST_MAPPING.equals(qualifiedName)) {
                    continue;
                }

                Set<String> methods = REQUEST_MAPPING.equals(qualifiedName)
                        ? extractRequestMethods(annotation)
                        : Set.of(METHOD_ANNOTATIONS.get(qualifiedName));
                List<PathValue> methodPaths = extractPaths(annotation);
                Module module = ModuleUtilCore.findModuleForPsiElement(method);

                for (PathValue classPath : classPaths) {
                    for (PathValue methodPath : methodPaths) {
                        String fullPath = joinPaths(classPath.path(), methodPath.path());
                        PsiElement navigationElement = methodPath.source() == null
                                ? annotation
                                : methodPath.source();
                        routes.add(new ApiRoute(
                                methods,
                                fullPath,
                                className,
                                safeName(method.getName(), "handler"),
                                module,
                                SmartPointerManager.getInstance(project)
                                        .createSmartPsiElementPointer(navigationElement)
                        ));
                    }
                }
            }
        }
        return routes;
    }

    private @NotNull List<PathValue> extractClassPaths(@NotNull PsiClass psiClass) {
        PsiModifierList modifierList = psiClass.getModifierList();
        if (modifierList == null) {
            return List.of(new PathValue("", null));
        }
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (REQUEST_MAPPING.equals(resolveAnnotationName(annotation))) {
                return extractPaths(annotation);
            }
        }
        return List.of(new PathValue("", null));
    }

    private @NotNull List<PathValue> extractPaths(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("path");
        if (value == null) {
            value = annotation.findDeclaredAttributeValue("value");
        }
        if (value == null) {
            return List.of(new PathValue("", annotation));
        }

        List<PathValue> result = new ArrayList<>();
        collectStringValues(value, result);
        return result.isEmpty() ? List.of(new PathValue("", annotation)) : result;
    }

    private void collectStringValues(
            @NotNull PsiAnnotationMemberValue value,
            @NotNull List<PathValue> result
    ) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                collectStringValues(initializer, result);
            }
            return;
        }

        Object constant = JavaPsiFacade.getInstance(project)
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        if (constant instanceof String path) {
            result.add(new PathValue(path, value));
            return;
        }

        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String path) {
            result.add(new PathValue(path, value));
        }
    }

    private @NotNull Set<String> extractRequestMethods(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("method");
        if (value == null) {
            return Set.of();
        }
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        collectRequestMethods(value, methods);
        return Set.copyOf(methods);
    }

    private void collectRequestMethods(
            @NotNull PsiAnnotationMemberValue value,
            @NotNull Set<String> methods
    ) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                collectRequestMethods(initializer, methods);
            }
            return;
        }

        if (value instanceof PsiReferenceExpression reference) {
            String name = reference.getReferenceName();
            if (name != null) {
                methods.add(name.toUpperCase(Locale.ROOT));
            }
            return;
        }
        String text = value.getText();
        int dot = text.lastIndexOf('.');
        methods.add((dot < 0 ? text : text.substring(dot + 1)).toUpperCase(Locale.ROOT));
    }

    private @Nullable String resolveAnnotationName(@NotNull PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName != null && qualifiedName.contains(".")) {
            return qualifiedName;
        }
        PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        if (reference != null && reference.resolve() instanceof PsiClass annotationClass) {
            return annotationClass.getQualifiedName();
        }
        return qualifiedName;
    }

    private static @NotNull String joinPaths(@Nullable String left, @Nullable String right) {
        String first = left == null ? "" : left.trim();
        String second = right == null ? "" : right.trim();
        if (first.isEmpty()) {
            return UrlInputParser.normalizePath(second);
        }
        if (second.isEmpty()) {
            return UrlInputParser.normalizePath(first);
        }
        return UrlInputParser.normalizePath(first + "/" + second);
    }

    private static @NotNull String safeName(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record PathValue(@NotNull String path, @Nullable PsiElement source) {
    }
}
