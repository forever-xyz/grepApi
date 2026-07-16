# Contributing / 参与贡献

Thank you for helping improve Grep API. 感谢你帮助改进 Grep API。

## Before You Start / 开始之前

1. Search existing issues before creating a new one. 提交前请先搜索已有 Issue。
2. Use a focused issue or pull request for one change. 每个 Issue 或 PR 请聚焦一个改动。
3. Do not include private source code, credentials, API tokens, or customer URLs. 不要提交私有源码、密码、Token 或客户 URL。

## Development / 开发环境

- JDK 17 or later / JDK 17 或更高版本
- IntelliJ IDEA 2024.1 or later / IntelliJ IDEA 2024.1 或更高版本
- The included Gradle Wrapper / 使用项目自带 Gradle Wrapper

Build and test:

```powershell
.\gradlew.bat clean test buildPlugin verifyPluginStructure
```

Use a local IDEA SDK when available:

```powershell
.\gradlew.bat clean test buildPlugin verifyPluginStructure `
  "-PlocalIdePath=D:/path/to/IntelliJ IDEA 2024.1"
```

## Pull Requests / Pull Request 要求

- Keep behavior backward-compatible with IntelliJ IDEA build 241 unless the change is explicitly version-specific.
- Add or update tests for matching, parsing, and route extraction changes.
- Keep UI text concise and update both Chinese and English documentation when behavior changes.
- Run the full test and plugin-structure checks before submitting.
- Explain the problem, the solution, and the verification performed in the PR description.

## License / 许可证

By submitting a contribution, you agree that it may be distributed under the repository's Apache License 2.0.

提交贡献即表示你同意该贡献可以按照本仓库的 Apache License 2.0 进行分发。
