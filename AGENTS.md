# AGENTS.md

## 项目概览

- 本项目是 [Cap](https://github.com/tiagozip/cap) server 协议的 Java 实现，当前兼容 `capjs-core` 0.1.1。
- 目标运行环境为 Java 17+，使用 Maven 构建，并通过 `mise` 调用 Maven。
- 这是库项目，不包含 Web 框架、JSON databind 或认证中间件；调用方自行暴露 `challenge` 和 `redeem` 端点。
- 3.x 的公开入口是 `Cap` / `CapBuilder`；不存在 2.x `CapManager`、`CapStore` 或 `validateCapToken` 兼容层。

## 仓库结构

- `src/main/java/github/luckygc/cap/`：公开门面、选项、协议 records 和扩展接口。
- `src/main/java/github/luckygc/cap/internal/`：默认门面及协议、加密、JSON、instrumentation、重放保护和 token 实现；不属于公开 API。
- `src/main/java/github/luckygc/cap/utils/`：Format 1 协议兼容随机数辅助代码。
- `src/test/java/`：JUnit 6（JUnit Jupiter）与 AssertJ 测试。
- `src/test/resources/fixtures/capjs-core-0.1.1/`：锁定上游行为的互操作 fixture。
- `tools/fixtures/`：需要 Node 24 和上游源码 checkout 的可选 fixture 复核工具。
- `tools/widget-e2e/`：固定 npm artifact、驱动真实 Chromium 调用 Java 回环 HTTP 测试后端的可选 E2E 工具。
- `docs/protocol-compatibility.md`：协议字段、失败码、加密 wire 与 fixture 来源。

## 常用命令

使用 `mise` 提供的 Maven：

```bash
mise exec maven -- mvn compile
mise exec maven -- mvn spotless:check
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn test
mise exec maven -- mvn verify
mise exec maven -- mvn -Dtest=ProtocolSecurityTest test
mise exec maven -- mvn -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test
```

- `spotless:apply` 使用 Google Java Format AOSP 风格自动格式化 Java；不要手工绕过格式检查。
- `test` 必须实际执行测试，不允许出现 `Tests are skipped.`。
- `verify` 必须通过 Spotless、全部测试和打包。
- 使用 `-Dtest=类名` 或 `-Dtest=类名#方法名` 运行聚焦测试。
- 常规 `test` / `verify` 只要求 Java 17+，不依赖 PATH 中的 Node。
- `cap.nodeChecks=true` 会额外用 PATH 中的 Node 24 检查 instrumentation JavaScript 语法和运行语义。

真实 widget E2E 必须显式启用，并在仓库外准备固定依赖：

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
npm init -y
npm install --save-exact @cap.js/widget@0.1.56 @cap.js/wasm@0.0.7 playwright@1.52.0
npx playwright install chromium
cd "$repo"
mise exec maven -- mvn -Pwidget-e2e -Dcap.widget.dir="$tmp" verify
```

- 默认 `mvn test` / `mvn verify` 只依赖 Java 17，不执行也不 skip `WidgetBrowserIT`，不探测 Node 或 Chromium。
- 显式 profile 缺少 `package-lock.json`、精确 version/resolved URL/integrity、Node、Chromium 或 artifact 文件时必须硬失败，不能静默 skip。
- E2E 在真实 Chromium 中只加载本地 widget/WASM，经真实回环 HTTP 覆盖 Format 1 成功、原始 redeem replay=`already_redeemed`、Format 1 instrumentation 成功、Format 2 RSW 成功，以及 STRICT 自动化拦截=`instr_automated_browser`；浏览器不得访问 CDN。
- E2E 输出不得包含 secret、JWT、solution、业务 token 或 tokenKey。测试 server 只验证互操作；库仍不提供 Web 框架、JSON databind、认证、CORS 或 CSRF，实际端点与边界策略由宿主应用负责。

## 代码约定

- 遵循 Java 17，不引入更高版本的语言特性或 API。
- 包名保持在 `github.luckygc.cap` 下；公开 API 放根包，默认实现按职责放 `internal` 子包。
- 使用 Spotless、Google Java Format AOSP 风格和 4 空格缩进；类名 `UpperCamelCase`，方法/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。
- 所有包保持 `@NullMarked`；可空类型及泛型元素使用 JSpecify `@Nullable` 精确标注。
- 公开接口和非显然逻辑使用简洁中文 Javadoc；注释说明语义或原因，不复述代码。
- 优先使用 Java JCA/JCE；协议专用 RSW 使用 `BigInteger`。新增依赖必须有明确必要性并在 `pom.xml` 集中管理版本。
- 避免与任务无关的重构、重命名或大范围格式化。

## 行为与兼容性

- 将 `Cap`、`CapBuilder`、选项类、扩展接口和公开 records 视为稳定公共 API；变更前评估源码、二进制和 wire 兼容性。
- 以上游 `capjs-core` 0.1.1 为协议事实来源；与旧设计文档冲突时以上游 fixture 和源码为准。
- DEFAULT 是 Format 1 SHA-256 PoW；只有显式 `instrumentation(...)` 才增加 Format 1 instrumentation。
- STRICT 是 Format 2，默认顺序为 RSW、instrumentation，默认 2048-bit RSW、`t=75000`、instrumentation level 3 且拦截自动化浏览器。
- `protocols(...)` 的 Format 2 语义与上游一致：空参数回退为 RSW，重复协议按输入顺序保留，null 数组或元素非法。
- challenge JWT 使用 HS256；Format 2 元数据 AES-GCM key 为 `HMAC-SHA256(secret, "cap:fmt2-v1")`，无 AAD，wire 为 `iv || tag || ciphertext`。Format 1 instrumentation 使用 info `cap:enc-v1`。
- 默认 Caffeine nonce consumer 仅保证单 JVM 原子消费；集群必须配置共享、原子的 `NonceConsumer`。
- 防重放 key 是 challenge JWT 签名的十六进制，成功消费后直到 challenge 剩余 TTL 到期都应拒绝重放。
- 默认兑换 token 使用 `token` / `tokenKey` 分离模型；只保存 `tokenKey`，不得记录或持久化明文 token。
- 不降低随机数、哈希、JWT、AES-GCM 或 token 校验强度，不在日志、异常、事件或失败响应中暴露 secret、token、solution、签名或内部摘要。
- `NonceConsumer`、`TokenSigner`、`CapEventListener`、`InstrumentationTransformer` 是同步受信回调；必须线程安全、有界，并由调用方处理超时与外部副作用。

## 测试约定

- 使用 JUnit 6（JUnit Jupiter）和 AssertJ，测试放在与生产包对应的 `src/test/java` 路径下。
- 测试类和方法命名沿用现有风格；`@DisplayName` 使用简洁中文描述行为。
- 修复缺陷先添加能复现问题的回归测试；新增行为覆盖成功、失败、边界和必要的并发路径。
- 协议变更必须使用 `capjs-core` 0.1.1 fixture 或直接上游 oracle 证明兼容，不能只让 Java 实现自洽。
- 涉及时间、随机性或过期逻辑时避免脆弱的精确时刻断言，使用注入时钟/随机源、范围或可观察行为。
- Node 检查保持 opt-in，不得让常规 Maven 构建依赖本机 Node。
- 只有 Maven 输出测试用例数量且未显示 `Tests are skipped.` 时，才能声称测试已执行并通过。

## 完成前检查

1. 检查改动是否局限于任务范围，公开 API、失败码或协议 wire 是否意外变化。
2. 运行 `mise exec maven -- mvn spotless:check`。
3. 运行 `mise exec maven -- mvn test`，确认测试数量、零失败且未跳过。
4. 运行 `mise exec maven -- mvn verify`，确认测试、打包和生命周期检查通过。
5. 运行 `git diff --check`，避免空白错误。
6. 若修改协议或 fixture，按 `docs/protocol-compatibility.md` 运行相应 Node 24 上游复核。
7. 若任何命令无法执行，在交付说明中列出命令、原因和未验证风险。

修改 `pom.xml`、目录布局、公开 API、协议语义、fixture 或测试策略后，应同步更新本文件和相关兼容性文档。
