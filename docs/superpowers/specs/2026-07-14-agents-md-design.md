# AGENTS.md 设计说明

## 目标

在仓库根目录创建一份简洁、中文、仓库专用的 `AGENTS.md`，让自动化编码代理能够快速理解项目约束，并在修改代码时通过 `mise` 使用正确的构建、检查和测试命令。

## 作用域

`AGENTS.md` 放置在仓库根目录，适用于整个仓库。当前不创建子目录级 `AGENTS.md`，也不规定仓库中尚未存在的发布、分支或提交信息流程。

## 内容结构

文档控制在约 60–90 行，包含以下部分：

1. 项目概览：说明这是 Java 17 实现的 CAP server 模块，使用 Maven Wrapper 构建。
2. 仓库结构：概述生产代码、测试、资源和 Checkstyle 配置的位置。
3. 常用命令：列出编译、校验和显式运行测试的命令。
4. 代码约定：要求遵循现有包结构、Google 风格 Checkstyle、中文 Javadoc 和测试显示名等既有模式。
5. 行为约束：避免无关重构，谨慎修改公开 API，并保持 challenge 兑换和一次性 CAP token 的既有语义。
6. 测试要求：按改动范围补充或更新 JUnit 5/AssertJ 测试，并说明 Maven Surefire 当前默认跳过测试。
7. 完成检查：要求至少执行 Checkstyle 和显式测试，并在无法执行时说明原因。

## 命令语义

- `mise exec maven -- mvn compile` 用于编译主代码。
- `mise exec maven -- mvn verify` 会触发绑定在 `validate` 阶段的 Checkstyle。
- 由于 `maven-surefire-plugin` 写死了 `<skip>true</skip>`，文档不得声称普通 `verify` 或 `test` 已运行测试。
- `-DskipTests=false` 等命令行参数不能覆盖该固定配置；需要实际运行测试时，先将 `skip` 改为 `false`，再使用 `mise exec maven -- mvn test`，并按任务范围决定是否保留该配置改动。

## 边界与维护原则

- 文档只记录能够从仓库配置和源码验证的约定。
- 不加入泛化的代理人格说明或与本仓库无关的工作流。
- 不重复 README 的用户使用教程，只提供完成代码修改所需的开发信息。
- 当 `pom.xml`、目录布局、公开 API 或测试策略变化时，应同步更新 `AGENTS.md`。

## 验收标准

- 根目录存在唯一的 `AGENTS.md`。
- 内容为简洁中文，命令、类名和配置名保留英文原文。
- 构建与测试说明准确反映 `pom.xml`，特别是测试默认跳过这一事实。
- 文档明确代码风格、公开 API、核心 token 语义和改动验证要求。
- 文档不引入未经仓库证据支持的流程约束。
