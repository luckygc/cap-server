# AGENTS.md

## 项目概览

- 本项目是 [Cap](https://github.com/tiagorangel1/cap) server 模块的 Java 实现。
- 目标运行环境为 Java 17+，使用 Maven 构建，并通过 `mise` 调用 Maven。
- 这是一个库项目，不包含 Web 框架集成；调用方自行暴露 `challenge` 和 `redeem` 端点。

## 仓库结构

- `src/main/java/github/luckygc/cap/`：公开接口、配置、模型和存储抽象。
- `src/main/java/github/luckygc/cap/impl/`：默认构建器、管理器和内存存储实现。
- `src/main/java/github/luckygc/cap/utils/`：随机数与消息辅助代码。
- `src/main/resources/`：本地化消息资源。
- `src/test/java/`：JUnit 6（JUnit Jupiter）测试。

## 常用命令

使用 `mise` 提供的 Maven：

```bash
mise exec maven -- mvn compile
mise exec maven -- mvn verify
mise exec maven -- mvn test
mise exec maven -- mvn -Dtest=CreateChallengeTest test
mise exec maven -- mvn -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test
```

- `compile`：编译主代码，并执行 Maven 生命周期中更早阶段的检查。
- `verify`：执行完整构建流程、Spotless 检查、测试和打包。
- 使用 `mise exec maven -- mvn spotless:apply` 自动格式化 Java。
- `mise exec maven -- mvn test` 必须实际执行测试，不允许出现 `Tests are skipped.`。
- `mise exec maven -- mvn verify` 必须通过 Spotless、测试和打包。
- 使用 `-Dtest=类名` 或 `-Dtest=类名#方法名` 运行聚焦测试。
- 常规 `test` / `verify` 只要求 Java 17+ 和通过 `mise` 提供的 Maven，不依赖 PATH 中的 Node。
- instrumentation 的 Node 语法与运行语义检查是显式 opt-in；使用上述 `cap.nodeChecks=true` 命令时，PATH 中需有 Node 24。
- `tools/fixtures/` 下的 instrumentation fixture 重生成/复核脚本同样需要 PATH 中的 Node 24；项目未配置 `mise` Node 工具名。

## 代码约定

- 遵循 Java 17 和项目现有写法，不引入高于 Java 17 的语言或 API 要求。
- 包名保持在 `github.luckygc.cap` 下，并按现有 `config`、`model`、`impl`、`utils` 职责放置代码。
- 使用 Spotless 和 Google Java Format 的 AOSP 风格；不要手工绕过规则。
- 使用 4 空格缩进，类名使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 公开接口和非显然逻辑沿用现有中文 Javadoc；注释说明原因或语义，不复述代码。
- 优先复用现有依赖和工具类；新增依赖必须有明确必要性，并集中在 `pom.xml` 管理版本。
- 保持空值标注和现有 JSpecify 用法一致。

## 行为与兼容性

- 将 `CapManager`、`CapStore`、配置类和公开模型视为公共 API；修改签名或语义前评估向后兼容性。
- `CapBuilder.protocols(...)` 的 Format 2 语义与上游一致：空参数回退为 RSW，重复协议按输入顺序保留并执行，null 数组或元素非法。
- challenge 必须在兑换时被消费，过期或重复兑换应失败。
- CAP token 具有一次性语义，默认在验证成功后消费；仅 `validateCapToken(token, true)` 显式保留 token。
- 不降低随机数、哈希或 token 校验强度，不在日志或错误信息中暴露 token、解答或内部摘要。
- 修改存储实现时保持 `CapStore` 语义，并考虑过期数据清理与并发访问。
- 避免与任务无关的重构、重命名或格式化。

## 测试约定

- 使用 JUnit 6（JUnit Jupiter）和 AssertJ，测试放在与生产包对应的 `src/test/java` 路径下。
- 测试类和方法命名沿用现有风格；`@DisplayName` 使用简洁中文描述行为。
- 修复缺陷时添加能够先复现问题的回归测试；新增行为覆盖成功、失败和边界路径。
- 涉及时间、随机性或过期逻辑时避免脆弱的精确时刻断言，使用范围或可观察行为断言。
- 只有 Maven 输出测试用例数量且未显示 `Tests are skipped.` 时，才能声称测试已执行并通过。

## 完成前检查

1. 检查改动是否局限于任务范围，公开 API 或协议语义是否意外变化。
2. 运行 `mise exec maven -- mvn verify`，确认 Spotless、测试和打包通过。
3. 确认 Maven 输出测试用例数量且未显示 `Tests are skipped.`。
4. 检查 `git diff --check`，避免空白错误。
5. 若任何命令无法执行，在交付说明中列出命令、原因和未验证风险。

修改 `pom.xml`、目录布局、公开 API 或测试策略后，应同步更新本文件。
