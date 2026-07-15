# Widget 与 Java 后端真实 E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加固定 `@cap.js/widget@0.1.56` 在真实 Chromium 中经回环 HTTP 调用 Java `Cap` 的 opt-in 端到端测试。

**Architecture:** 默认 Surefire 运行可独立验证的 test-only wire adapter；`widget-e2e` profile 用 Failsafe 运行 `WidgetBrowserIT`。IT 以 JDK `HttpServer` 暴露真实 challenge/redeem endpoint，并调用 Node Playwright 驱动固定 widget/WASM artifact，不改变生产 API 或默认 Java-only 构建。

**Tech Stack:** Java 17、Maven Surefire/Failsafe 3.5.6、JUnit 6、AssertJ、JDK HttpServer、Node 24、Playwright 1.52.0、Chromium、widget 0.1.56、WASM 0.0.7。

## Global Constraints

- 不增加生产依赖，不修改公开 API 或协议 wire。
- 默认 `mvn test/verify` 只要求 Java 17，且不得产生浏览器测试 skip。
- widget、WASM、Playwright 精确锁定 version、resolved URL 和 integrity；浏览器不访问 CDN。
- HTTP/JSON adapter 只放 test source；不引入 JSON databind 或 Web 框架。
- 测试输出不得包含 secret、JWT、solution、业务 token 或 tokenKey。
- Java 使用 Spotless AOSP 格式；改测试策略后同步更新 `AGENTS.md` 和兼容性文档。

---

### Task 1: 锁定 Maven 生命周期

**Files:**
- Create: `src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: `${failsafe.version}=3.5.6`；profile `widget-e2e`；profile 内 Failsafe 的 `integration-test`/`verify` goals。

- [ ] **Step 1: 写失败的生命周期契约测试**

```java
@DisplayName("Maven 测试生命周期契约")
class BuildLifecycleContractTest {
    @Test
    @DisplayName("widget E2E 仅由显式 Failsafe profile 执行")
    void widgetE2eIsOptIn() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains(
                "<failsafe.version>3.5.6</failsafe.version>",
                "<id>widget-e2e</id>",
                "<artifactId>maven-failsafe-plugin</artifactId>",
                "<goal>integration-test</goal>",
                "<goal>verify</goal>");
        assertThat(pom.indexOf("<artifactId>maven-failsafe-plugin</artifactId>"))
                .isGreaterThan(pom.indexOf("<profiles>"));
    }
}
```

- [ ] **Step 2: 验证 RED**

Run: `mise exec maven -- mvn -Dtest=BuildLifecycleContractTest test`

Expected: FAIL，缺少 Failsafe property/profile/plugin。

- [ ] **Step 3: 添加最小 profile**

在 properties 增加 `<failsafe.version>3.5.6</failsafe.version>`；在 project 末尾增加：

```xml
<profiles>
    <profile>
        <id>widget-e2e</id>
        <build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>${failsafe.version}</version>
            <executions><execution><goals>
                <goal>integration-test</goal><goal>verify</goal>
            </goals></execution></executions>
        </plugin></plugins></build>
    </profile>
</profiles>
```

不配置 Surefire includes，也不使用 JUnit condition skip；依靠 Surefire/Failsafe 默认命名边界。

- [ ] **Step 4: 验证 GREEN 和默认生命周期**

Run: `mise exec maven -- mvn -Dtest=BuildLifecycleContractTest test`

Expected: 1 test，0 failures/errors/skipped。

Run: `mise exec maven -- mvn test`

Expected: `BUILD SUCCESS`，没有 `WidgetBrowserIT` 或 `Tests are skipped.`。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java
git commit -m "test: 添加 widget E2E opt-in 生命周期"
```

---

### Task 2: 实现 widget wire adapter

**Files:**
- Create: `src/test/java/github/luckygc/cap/widget/WidgetWireAdapterTest.java`
- Create: `src/test/java/github/luckygc/cap/widget/WidgetWireAdapter.java`

**Interfaces:**
- Produces: `byte[] encodeChallenge(ChallengeResponse)`、`RedeemRequest decodeRedeem(byte[])`、`byte[] encodeResult(RedeemResult)`。

- [ ] **Step 1: 写失败测试**

```java
@DisplayName("Widget HTTP JSON adapter 测试")
class WidgetWireAdapterTest {
    private final ProtocolJsonCodec json = new ProtocolJsonCodec();
    private final WidgetWireAdapter adapter = new WidgetWireAdapter();

    @Test
    void encodesFormat1WithoutNullInstrumentation() {
        Map<String, Object> wire = object(adapter.encodeChallenge(
                new ChallengeResponse.Format1(
                        new ChallengeResponse.Challenge(1, 4, 1), "jwt", 123L, null)));
        assertThat(wire).containsOnlyKeys("challenge", "token", "expires");
    }

    @Test
    void decodesSnakeCaseInstrumentationSignals() {
        RedeemRequest request = adapter.decodeRedeem(json.writeObject(Map.of(
                "token", "jwt", "solutions", List.of(7L),
                "instr", Map.of("i", "id", "state", Map.of("a", 1L), "ts", 9L),
                "instr_blocked", true, "instr_timeout", false)));
        assertThat(request.instrBlocked()).isTrue();
        assertThat(request.instrTimeout()).isFalse();
        assertThat(request.instr().state()).containsEntry("a", 1L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(byte[] value) {
        return (Map<String, Object>) (Map<?, ?>) json.readObject(value);
    }
}
```

测试数据必须包含 Format 1 null instrumentation、Format 2 RSW payload、带 `i/state/ts` 的 redeem、
`RedeemResult.Success(tokenKey="server-key")` 和 `Failure(reason="already_redeemed")`。成功 JSON 只允许
`success/token/expires`；失败 JSON 必须是 `success/reason/instr_error/error` 且 error 等于 reason。

- [ ] **Step 2: 验证 RED**

Run: `mise exec maven -- mvn -Dtest=WidgetWireAdapterTest test`

Expected: test compilation FAIL，找不到 `WidgetWireAdapter`。

- [ ] **Step 3: 实现最小 adapter**

```java
final class WidgetWireAdapter {
    private final ProtocolJsonCodec json = new ProtocolJsonCodec();

    byte[] encodeChallenge(ChallengeResponse response) {
        return json.writeObject(challengeMap(response));
    }

    RedeemRequest decodeRedeem(byte[] body) {
        Map<String, @Nullable Object> wire = json.readObject(body);
        String token = requiredString(wire.get("token"), "token");
        List<@Nullable Object> solutions = requiredList(wire.get("solutions"), "solutions");
        return new RedeemRequest(
                token,
                solutions,
                instrumentation(wire.get("instr")),
                Boolean.TRUE.equals(wire.get("instr_blocked")),
                Boolean.TRUE.equals(wire.get("instr_timeout")));
    }

    byte[] encodeResult(RedeemResult result) {
        if (result instanceof RedeemResult.Success success) {
            return json.writeObject(Map.of(
                    "success", true, "token", success.token(), "expires", success.expires()));
        }
        RedeemResult.Failure failure = (RedeemResult.Failure) result;
        return json.writeObject(Map.of(
                "success", false,
                "reason", failure.reason(),
                "instr_error", failure.instrError(),
                "error", failure.reason()));
    }
}
```

实现要求：Format 1 null instrumentation 省略；Format 2 protocol/payload 顺序不变；decode 只接受 String
token、List solutions、Map instr、String i、String-key state、可空整数 ts；非法结构抛
`IllegalArgumentException`；不得返回 tokenKey/scope/iat，不得使用 databind/反射。

- [ ] **Step 4: 格式化并验证 GREEN**

Run: `mise exec maven -- mvn spotless:apply`

Run: `mise exec maven -- mvn -Dtest=WidgetWireAdapterTest test`

Expected: 4 tests，0 failures/errors/skipped。

- [ ] **Step 5: 提交**

```bash
git add src/test/java/github/luckygc/cap/widget
git commit -m "test: 添加 widget HTTP wire adapter"
```

---

### Task 3: 锁定 npm artifacts

**Files:**
- Create: `tools/widget-e2e/widget-assets.test.mjs`
- Create: `tools/widget-e2e/widget-assets.mjs`

**Interfaces:**
- Produces: async `resolveWidgetAssets(root)` → `{widgetScript, wasm, playwrightModule}` absolute paths。

- [ ] **Step 1: 写失败的 Node tests**

用 `node:test` 和临时目录覆盖：正确 lock 成功、篡改 integrity 失败、缺 artifact 文件失败。固定 entries：

```js
export const EXPECTED_PACKAGES = {
  "node_modules/@cap.js/widget": {
    version: "0.1.56",
    resolved: "https://registry.npmjs.org/@cap.js/widget/-/widget-0.1.56.tgz",
    integrity: "sha512-j640dNNNIF8IWmwqmSx0ihgU8sz/6Jm9mHveeDWUk8aXVqFm+2TSsp5bawtMtgf0aa7rFkmT9p76jrqO1uSEpQ==",
  },
  "node_modules/@cap.js/wasm": {
    version: "0.0.7",
    resolved: "https://registry.npmjs.org/@cap.js/wasm/-/wasm-0.0.7.tgz",
    integrity: "sha512-IgUjrPOUBaOjTp+BkrhfEBBeQ4An7fQiSWWezDy9Uvd+OdTYm4+h3AJU0j/CpHYayp7FltZU+UePC6p28oGQaw==",
  },
  "node_modules/playwright": {
    version: "1.52.0",
    resolved: "https://registry.npmjs.org/playwright/-/playwright-1.52.0.tgz",
    integrity: "sha512-JAwMNMBlxJ2oD1kce4KPtMkDeKGHQstdpFPcPH3maElAXon/QZeTvtsfXmTMRyO9TslfoYOXkSsvao2nE1ilTw==",
  },
};
```

- [ ] **Step 2: 验证 RED**

Run: `node --test tools/widget-e2e/widget-assets.test.mjs`

Expected: FAIL `ERR_MODULE_NOT_FOUND` for `widget-assets.mjs`。

- [ ] **Step 3: 实现解析器**

读取 `<root>/package-lock.json` 的 `packages`，逐字段严格比较上述 entries，再 `access()`：

```text
node_modules/@cap.js/widget/cap.min.js
node_modules/@cap.js/wasm/browser/cap_wasm_bg.wasm
node_modules/playwright/index.mjs
```

任何缺失或不一致抛不含本机敏感路径的 Error；不访问网络、不接受 semver range。

- [ ] **Step 4: 验证 GREEN**

Run: `node --test tools/widget-e2e/widget-assets.test.mjs`

Expected: 3 tests，0 failures/skipped。

- [ ] **Step 5: 提交**

```bash
git add tools/widget-e2e/widget-assets.mjs tools/widget-e2e/widget-assets.test.mjs
git commit -m "test: 锁定 widget E2E npm artifacts"
```

---

### Task 4: 建立真实 Chromium/HTTP/Java E2E

**Files:**
- Create: `tools/widget-e2e/run-widget-e2e.mjs`
- Create: `src/test/java/github/luckygc/cap/widget/WidgetBrowserIT.java`

**Interfaces:**
- Consumes: `-Dcap.widget.dir`、Task 2 adapter、Task 3 asset resolver。
- Produces: 五场景无敏感信息 JSON summary；Java server observations。

- [ ] **Step 1: 写失败的 IT 外壳**

`WidgetBrowserIT` 必须读取非空 `cap.widget.dir`，在 loopback 随机端口启动 `HttpServer`，用
`ProcessBuilder("node", "tools/widget-e2e/run-widget-e2e.mjs", "--npm-root", npmRoot,
"--base-url", baseUrl)`
运行 driver，三分钟超时，finally 中 `server.stop(0)`。初始 handler 全部 404。

- [ ] **Step 2: 准备环境并验证 RED（driver 缺失）**

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

Expected: Failsafe 执行 `WidgetBrowserIT` 并因 Node driver 不存在失败。

- [ ] **Step 3: 写最小 driver 并验证失败推进到 HTTP 404**

driver 解析两个参数，调用 `resolveWidgetAssets`，用 `pathToFileURL(playwrightModule)` import Chromium，
打开 `${baseUrl}/?scenario=format1`，调用 `cap.solve()`，finally 关闭 browser。

Run: Step 2 Maven command。

Expected: FAIL from page HTTP 404，证明真实浏览器和 HTTP 已执行。

- [ ] **Step 4: 实现 Java test server routes**

将 server 封装为 nested `WidgetServer implements AutoCloseable`，支持：

```text
GET /?scenario=name         -> local widget HTML
GET /widget.js              -> npm cap.min.js
GET /cap_wasm_bg.wasm       -> npm browser WASM
POST /name/challenge        -> Cap.createChallenge + adapter
POST /name/redeem           -> adapter + Cap.redeem + adapter
```

场景使用独立 Cap：format1=`format1(2,8,2)`；instrumented=`format1(1,4,1)` + instrumentation
block false；format2=STRICT + protocols(RSW)；strict=STRICT 默认 RSW+instrumentation。两个 Format 2
场景复用一次 1024-bit test key，并把 `rswIterations` 降到 1000 以控制 E2E 时间；这只改变测试成本，
不改变被验证的 wire 和协议顺序。

HTML 固定本地 WASM URL、`CAP_SILENT=true`、endpoint `/<scenario>/`，并把 solve/error event 写入
`window.__capResult`。请求体最大 64 KiB；超限 413、无效 JSON 400、协议失败 403、成功 200。
server 仅在内存记录各场景调用次数、最后 reason 和最后 redeem bytes，不打印内容。

- [ ] **Step 5: 扩展 driver 覆盖五场景**

对 format1/instrumented/format2 分别创建 page、监听真实 redeem request、调用 solve，断言 solve event、
非空 token 和 `input[name=cap-token]` 值一致。将捕获的 format1 `postData()` 原样再次 POST，断言
403 + `already_redeemed`。strict 捕获 solve rejection，断言 error code `instr_blocked`，并等待 redeem
response 的 `instr_automated_browser`/`instr_error=true`。只有 strict 允许对应 `[instr_blocked]`
console error；其他 console error、pageerror、requestfailed、timeout 都失败。

stdout 只输出：

```json
{"format1":"solved","replay":"already_redeemed","instrumented":"solved","format2":"solved","strict":"instr_automated_browser"}
```

- [ ] **Step 6: 完成 JUnit 断言并验证 GREEN**

JUnit 断言 Node exit 0、上述五个 summary 值、每个成功场景 challenge/redeem 至少一次、format1 redeem
两次、最后 replay/strict reason。失败诊断只打印进程一般错误，不打印 raw body/token。

Run: `mise exec maven -- mvn spotless:apply`

Run: `node --test tools/widget-e2e/widget-assets.test.mjs`

Run: `mise exec maven -- mvn -Pwidget-e2e -Dcap.widget.dir="$tmp" verify`

Expected: `WidgetBrowserIT` 1 test，0 failures/errors/skipped，五个 summary 状态正确。

- [ ] **Step 7: 提交**

```bash
git add tools/widget-e2e/run-widget-e2e.mjs src/test/java/github/luckygc/cap/widget/WidgetBrowserIT.java
git commit -m "test: 验证 widget 调用 Java HTTP 后端"
```

---

### Task 5: 文档与完整验证

**Files:**
- Modify: `src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java`
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/protocol-compatibility.md`

**Interfaces:**
- Produces: 可复制的固定依赖安装与 E2E 命令；明确默认/opt-in 边界。

- [ ] **Step 1: 写失败的文档契约**

扩展测试断言三个文档分别包含 `-Pwidget-e2e`、`-Dcap.widget.dir`、widget 0.1.56、WASM 0.0.7、
Playwright 1.52.0、`instr_automated_browser`。

- [ ] **Step 2: 验证 RED**

Run: `mise exec maven -- mvn -Dtest=BuildLifecycleContractTest test`

Expected: FAIL，文档缺少 E2E 命令。

- [ ] **Step 3: 更新文档**

加入 Task 4 Step 2 的可复制命令，并说明默认 Maven 不依赖 Node/Chromium、profile 缺依赖硬失败、
覆盖真实 HTTP/Format 1/replay/instrumentation/Format 2 RSW/STRICT blocked，CORS/CSRF 仍由宿主负责。

- [ ] **Step 4: 验证文档契约**

Run: `mise exec maven -- mvn spotless:apply`

Run: `mise exec maven -- mvn -Dtest=BuildLifecycleContractTest test`

Expected: pass，0 skipped。

- [ ] **Step 5: 完整默认验证**

Run: `mise exec maven -- mvn spotless:check`

Run: `mise exec maven -- mvn test`

Run: `mise exec maven -- mvn verify`

Expected: 全部 `BUILD SUCCESS`，Maven 输出测试数量、0 failures/errors/skipped，默认不运行 IT。

- [ ] **Step 6: 完整 opt-in 验证**

Run: `node --test tools/widget-e2e/widget-assets.test.mjs`

Run: `mise exec maven -- mvn -Pwidget-e2e -Dcap.widget.dir="$tmp" verify`

Run: `mise exec maven -- mvn -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test`

Run: `git diff --check`

Expected: Node tests、默认 tests、`WidgetBrowserIT`、8 个 instrumentation tests 全部执行且 0
failures/errors/skipped；`git diff --check` 无输出。

- [ ] **Step 7: 提交**

```bash
git add AGENTS.md README.md docs/protocol-compatibility.md src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java
git commit -m "docs: 记录 widget Java E2E 验证"
```

---

## Plan Self-Review

- Spec coverage: Task 1 生命周期；Task 2 JSON wire；Task 3 artifact 身份；Task 4 HTTP/浏览器/五场景/清理；Task 5 文档与验证。
- Scope: 新 Java 类型全在 test source；生产 API、依赖和协议不变。
- Type consistency: adapter 方法、profile `widget-e2e`、property `cap.widget.dir` 在所有 task 中一致。
- Default safety: `*IT` 只由 profile 内 Failsafe 默认 include 发现，不使用 skip。
