# Widget 与 Java 后端真实 E2E 设计

## 目标

为 `cap-server` 增加显式 opt-in 的浏览器端到端验证：固定版本的
`@cap.js/widget` 在真实 Chromium 中通过 HTTP 获取 Java `Cap` 生成的 challenge，执行
PoW、RSW 或 instrumentation，并将真实 JSON redeem 请求交回 Java 后端验证。常规 Maven
生命周期继续只依赖 Java 17，不要求 PATH 中存在 Node 或浏览器。

## 范围

本次只增加测试工具、测试 profile 和文档，不增加生产依赖，不修改 `Cap` 公开 API，也不把
HTTP、JSON databind 或 Web 框架纳入库的生产职责。

覆盖以下场景：

1. Format 1 widget 完整求解，触发 `solve`，并写入隐藏 token 字段。
2. 重放浏览器实际发送的 redeem body，后端返回 `already_redeemed`。
3. Format 1 instrumentation 在真实 sandbox iframe 中执行并成功兑换。
4. Format 2 RSW 由 widget worker 求解并成功兑换。
5. STRICT 默认 instrumentation 在 headless Chromium 中报告 blocked，后端返回
   `instr_automated_browser`。
6. 失败响应向 widget 的 `error` 字段映射稳定 reason，使浏览器错误事件保留可诊断信息。

不覆盖宿主应用的 CORS、CSRF、认证中间件或特定 Spring/Jakarta 序列化配置；这些仍属于调用方。

## 架构

### Maven 生命周期

新增 Maven profile `widget-e2e`，使用 Failsafe 运行命名为 `*IT` 的测试。默认 Surefire
不发现该命名，因此 `mvn test` 和默认 `mvn verify` 不会跳过浏览器测试，也不会探测 Node。

显式 profile 要求调用方传入一个临时 npm 项目目录。该目录安装固定版本的 widget、WASM 和
Playwright，并已安装 Chromium。缺少任一前置条件时测试必须失败并给出可执行的准备命令，不能静默
skip。

### Java HTTP 测试后端

`WidgetBrowserIT` 使用 JDK `com.sun.net.httpserver.HttpServer` 监听回环地址和随机端口。测试后端
按场景持有独立 `Cap` 实例，并暴露：

- `GET /`：返回包含 `<cap-widget data-cap-api-endpoint="...">` 的最小 HTML；
- `GET /widget.js`：返回固定 npm artifact 的 widget bundle；
- `GET /cap_wasm_bg.wasm`：返回固定 WASM artifact；
- `POST /<scenario>/challenge`：序列化实际 `ChallengeResponse`；
- `POST /<scenario>/redeem`：解析 widget JSON、构造 `RedeemRequest`、调用实际 `Cap.redeem` 并映射响应。

测试 adapter 使用项目已有的受限 JSON codec，不引入 Jackson Databind。它显式处理
`instr_blocked`、`instr_timeout` 和 `instr_error`，成功响应只返回 widget 所需的 `success`、
`token`、`expires`；`tokenKey` 留在服务端测试状态中，不发送给浏览器。失败响应同时返回稳定
`reason` 和相同的安全 `error` 文本。

后端保存最近一次真实 redeem body，供防重放场景通过 HTTP 原样重发；不使用伪造 solution 或直接
调用验证器替代浏览器流程。

### Playwright 驱动

Node 脚本从传入 npm 项目解析 Playwright，从该项目的 `node_modules` 读取 widget/WASM，并读取
`package-lock.json` 校验以下身份：

- widget 包名和版本固定为 `@cap.js/widget@0.1.56`；
- WASM 包名和版本固定为 `@cap.js/wasm@0.0.7`；
- resolved URL 和 integrity 与测试代码中锁定值一致；
- Playwright 仅作为测试运行器，版本由准备命令精确固定。

脚本连接 Java 测试传入的 base URL，启动 headless Chromium。每个场景新建页面，调用 widget 的
`solve()`，监听 `solve` 或 `error` 事件，并以 JSON 行把结果返回 JUnit。浏览器控制台错误、页面错误、
请求失败或超时均导致测试失败；STRICT blocked 场景仅允许 Chromium 对预期 403 产生的一次固定资源
console error，其他未声明错误仍失败。为消除默认 instrumentation 从 18 类检查随机抽取 8 类带来的
概率性，STRICT 页面通过 init script 植入标准自动化标记；按真实 iframe init 时
`documentElement=null` 计算，仍有 11 类稳定命中、仅 7 类未命中，因而任意 8 类抽样必有命中。测试
保持默认 generator/transformer，不通过重试掩盖随机未命中。

所有资源由回环 HTTP server 本地提供；测试不在浏览器运行期间访问 CDN。

## 数据流

成功路径为：

1. JUnit 启动 Java HTTP server 和对应 `Cap`；
2. Playwright 打开场景页面并加载固定 widget/WASM；
3. widget `POST challenge`；
4. Java 调用 `Cap.createChallenge()` 并序列化响应；
5. widget worker/iframe 计算 solution；
6. widget `POST redeem`；
7. Java adapter 构造 `RedeemRequest` 并调用 `Cap.redeem()`；
8. widget 读取成功 token，发出 `solve` 并写入隐藏字段；
9. JUnit 校验浏览器结果与服务端记录。

STRICT blocked 路径在第 6 步发送 Format 2 `{blocked:true}` solution，Java 返回
`instr_automated_browser`；Playwright 校验后端 403 的 `reason/error=instr_automated_browser` 和
`instr_error=true`。真实 `@cap.js/widget@0.1.56` 证明原方案中“STRICT error event code 为
`instr_blocked`”的假设不成立：前端 `solve()` rejection message 为 `instr_automated_browser`，error
event code 经上游统一映射为 `invalid_solution`；`instr_blocked` event code 只属于 Format 1
instrumentation 分支。此处以真实 artifact 行为修正原方案。

## 错误处理与资源限制

- HTTP 请求体沿用协议 JSON 的 64 KiB 上限；超过上限返回 413。
- 不支持的方法或路径返回 404/405。
- JSON 结构错误返回带 `invalid_body` 的 400 响应。
- 协议失败返回 403；成功返回 200。
- Node 子进程和每个浏览器场景都有明确超时。
- JUnit 的 `finally`/`AfterAll` 必须终止 HTTP server 和 Node/Chromium；Node 脚本也必须在
  `finally` 中关闭页面与浏览器。
- 测试日志不得输出 secret、完整 challenge token、solution、业务 token 或 tokenKey。

## 测试与构建约束

TDD 顺序如下：

1. 先添加 Maven profile 契约测试，证明默认生命周期不发现 `*IT`，显式 profile 才执行。
2. 先写 Java HTTP adapter 的聚焦失败测试，再实现最小映射。
3. 先写 Node artifact 校验失败测试，再实现 package-lock 校验。
4. 逐个添加浏览器场景，每个场景先以缺失 route/行为失败，再补最小实现。
5. 最后运行 Spotless、完整 Maven test/verify、opt-in widget E2E、Node instrumentation 检查和
   `git diff --check`。

新增 profile 和测试策略后同步更新 `AGENTS.md`、`README.md` 与
`docs/protocol-compatibility.md`，明确默认构建与 opt-in 浏览器验证的边界及准备命令。

## 完成标准

- 默认 `mvn test` 与 `mvn verify` 仍只需 Java 17，测试输出不包含浏览器 E2E skip。
- opt-in 命令在干净临时 npm 项目中安装固定 artifact 后，真实 Chromium 场景全部通过。
- 测试能证明请求确实经过回环 HTTP，而不是 `CAP_CUSTOM_FETCH` 或内存 mock。
- Format 1、instrumentation、Format 2 RSW、STRICT blocked 和 replay 均有独立断言。
- 工作树只包含本任务相关测试、工具、构建配置和文档改动。
