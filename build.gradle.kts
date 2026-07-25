import com.sun.org.apache.bcel.internal.util.Args.require
import com.sun.tools.attach.spi.AttachProvider.providers

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.found404.grepapi"
version = "0.0.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath").orNull
        require(!localIdePath.isNullOrBlank()) {
            """
            Missing localIdePath. Set it in C:/Users/Administrator/.gradle/gradle.properties, for example:
            localIdePath=D:\tools\IntelliJ IDEA 2026.1.1
            This project intentionally uses a local IntelliJ IDEA SDK and never downloads a target IDE SDK.
            """.trimIndent()
        }
        local(localIdePath)
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

java {
    toolchain {
        // IntelliJ IDEA 2026 ships Java 21 class files. Build with JDK 21 so
        // the compiler can read that SDK, while JavaCompile below keeps the
        // plugin bytecode compatible with IDEA 2024's Java 17 runtime.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    test {
        useJUnitPlatform()
    }

    named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
        disabledPlugins.add("com.intellij.swagger")
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }

    named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
        disabledPlugins.add("com.intellij.swagger")
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.found404.grepapi"
        name = "Grep API"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "241"
        }

        changeNotes = """
            <ul>
                <li>Automatically selects the highest-ranked search result so pressing <code>Enter</code> opens it immediately.</li>
                <li>Restores the previous search text and selects it for quick paste replacement.</li>
                <li>Shows up to ten recently opened endpoints when the search field is empty, ordered newest first.</li>
                <li>Uses a more compact HTTP method filter and improves search popup interactions.</li>
            </ul>
        """.trimIndent()
    }
}
