package com.github.grepapi.spring;

import com.github.grepapi.model.ApiRoute;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;
import java.util.Set;

public final class SpringRouteExtractorTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface RequestMapping {
                    String[] value() default {};
                    String[] path() default {};
                    RequestMethod[] method() default {};
                }
                """);
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface GetMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
                """);
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public enum RequestMethod { GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS }
                """);
    }

    public void testCombinesClassAndMethodMappingsAndKeepsNavigationElement() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText(JavaFileType.INSTANCE, """
                package example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;

                @RequestMapping("/api/users")
                public class UserController {
                    @GetMapping("/{id}")
                    public String getUser() { return "ok"; }
                }
                """);

        List<ApiRoute> routes = new SpringRouteExtractor(getProject()).extract(file);

        assertSize(1, routes);
        ApiRoute route = routes.get(0);
        assertEquals("/api/users/{id}", route.path());
        assertEquals(Set.of("GET"), route.httpMethods());
        assertEquals("example.UserController", route.className());
        assertEquals("getUser", route.methodName());
        assertNotNull(route.navigationPointer().getElement());
        assertEquals("\"/{id}\"", route.navigationPointer().getElement().getText());
    }
}
