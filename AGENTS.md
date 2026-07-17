# Repository Guidelines

## Project Structure & Module Organization

Grep API is a Java 17 IntelliJ Platform plugin. Production code lives under `src/main/java/com/github/grepapi`, grouped by responsibility: `core` for parsing and matching, `spring` for route extraction, `service` for orchestration, `ui` for actions and popups, and `settings` for IDE configuration. Plugin metadata, icons, and other packaged resources are in `src/main/resources`; keep registrations in `META-INF/plugin.xml`. Tests mirror production packages under `src/test/java`. Documentation screenshots belong in `docs/images`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper; do not depend on a globally installed Gradle.

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPluginStructure
.\gradlew.bat runIde
.\gradlew.bat clean test buildPlugin verifyPluginStructure
```

These commands run the test suite, assemble the plugin ZIP, validate plugin metadata, launch a sandbox IDE, and perform the full pre-PR check respectively. On macOS/Linux, replace `.\gradlew.bat` with `./gradlew`. To test against a local IntelliJ installation, add `"-PlocalIdePath=D:/path/to/IntelliJ IDEA 2024.1"`.

## Coding Style & Naming Conventions

Follow the existing Java style: four-space indentation, braces on the same line, one public type per file, and explicit imports. Use `PascalCase` for classes and records, `camelCase` for methods and variables, and `UPPER_SNAKE_CASE` for constants. Keep packages lowercase beneath `com.github.grepapi`. Prefer small, responsibility-focused classes and preserve `@NotNull` annotations at IntelliJ API boundaries. No formatter or linter is configured, so match surrounding code and rely on IDE formatting.

## Testing Guidelines

Tests use JUnit 5, with Mockito for IntelliJ interfaces; JUnit 4 compatibility is also available. Name test classes `*Test` and test methods after observable behavior, for example `rejectsWrongHttpMethod`. Add focused coverage for parsing, matching/ranking, and Spring route extraction changes. Run `test` locally and the full pre-PR command before submission.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, sentence-style subjects such as `Document cross-platform API search shortcuts`. Keep each commit and PR focused. Create feature or fix branches targeting `develop`; only `develop` may target `master`. PR descriptions must explain the problem, solution, and verification, link relevant issues, and include screenshots for UI changes. Update both English and Chinese documentation when user-visible behavior changes, and retain compatibility with IntelliJ build 241 unless explicitly changing support.

## Security & Configuration

Never commit credentials, tokens, private source, customer URLs, local IDE paths, or generated sandbox/build output. Report vulnerabilities through the process in `SECURITY.md` rather than a public issue.
