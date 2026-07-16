plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.found404.grepapi"
version = "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath").orNull
        if (localIdePath.isNullOrBlank()) {
            intellijIdeaCommunity("2024.1.7") {
                useInstaller = false
            }
        } else {
            local(localIdePath)
        }
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
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        sourceCompatibility = "17"
        targetCompatibility = "17"
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
                <li>Initial public release.</li>
                <li>Fast Spring MVC endpoint search with fuzzy matching and HTTP method filters.</li>
                <li>Open search with <code>Ctrl + Alt + /</code> on Windows/Linux or <code>Command + Option + /</code> on macOS.</li>
                <li>Jump directly from a search result to the matching Controller method.</li>
                <li>Supports IntelliJ IDEA 2024.1 and later.</li>
            </ul>
        """.trimIndent()
    }
}
