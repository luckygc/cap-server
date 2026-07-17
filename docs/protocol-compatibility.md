# capjs-core 0.1.1 协议兼容性

本文记录 cap-server 3.0 与官方 npm artifact `capjs-core@0.1.1` 之间的 wire 约定。
fixture 以 package-lock 的 resolved URL 和 integrity 为权威来源；`f9ffadb` 仅是已核对的上游
语义参考 commit，不用于证明 npm tarball 的身份。宿主应用负责 HTTP 和 JSON 序列化，并必须显式处理 Web wire 与
核心 Java API 的命名差异。

## Profile 映射

| Profile | 格式 | 默认协议 | 默认安全参数 |
| --- | --- | --- | --- |
| `DEFAULT` | Format 1 | SHA-256 PoW | `c=50`、`s=32`、`d=4`；默认无 instrumentation |
| `STRICT` | Format 2 | RSW、instrumentation | 2048-bit RSW、`t=75000`、instrumentation level 3、拦截自动化浏览器 |

两者默认 challenge TTL 都是 10 分钟，兑换后的业务 token TTL 是 20 分钟。Format 1 可通过
`CapBuilder.instrumentation(...)` 启用 instrumentation。Format 2 的 `protocols(...)` 保留顺序和
重复项；空列表回退为 RSW。

## 公共响应与请求

Format 1 challenge 对应 `ChallengeResponse.Format1`：

```json
{
  "challenge": { "c": 50, "s": 32, "d": 4 },
  "token": "<HS256 JWT>",
  "expires": 1700000600000,
  "instrumentation": null
}
```

Format 2 challenge 对应 `ChallengeResponse.Format2`：

```json
{
  "format": 2,
  "challenges": [
    { "protocol": "sha256-pow", "payload": { "salt": "<hex>", "target": "0000" } },
    { "protocol": "rsw", "payload": { "N": "<hex>", "x": "<hex>", "t": 75000 } },
    { "protocol": "instrumentation", "payload": { "blob": "<base64>" } }
  ],
  "token": "<HS256 JWT>",
  "expires": 1700000600000
}
```

challenge 响应的 record component 与上游字段同名，可以直接序列化。redeem 不能直接把核心
records 当作兼容 DTO：Web wire 使用 snake_case，Java API 使用 camelCase。映射如下：

| Web wire | Java API |
| --- | --- |
| 请求 `instr_blocked` | `RedeemRequest.instrBlocked()` |
| 请求 `instr_timeout` | `RedeemRequest.instrTimeout()` |
| 失败响应 `instr_error` | `RedeemResult.Failure.instrError()` |

Web 请求经 adapter 后对应
`RedeemRequest(token, solutions, instr, instrBlocked, instrTimeout)`：

- Format 1：`solutions` 是与 `c` 等长的 JSON Number 数组；与上游 `JSON.parse` 一样，所有值先按
  binary64 舍入，再用 JavaScript `String(number)` 文本参与哈希，因此也接受小数、指数形式、
  超出安全整数范围的值，以及 `1e400` / `-1e400` 溢出得到的 `Infinity` / `-Infinity`；
  `NaN` 不是合法 JSON 且 Java API 也拒绝它。仅 `RedeemRequest.solutions` 允许直接传入正负
  Infinity，challenge extra 与 instrumentation state 仍要求有限浮点值。启用 instrumentation 时，顶层 `instr` 包含
  `i`、`state`、可空 `ts`，顶层 Web 字段 `instr_blocked` / `instr_timeout` 传递浏览器信号。
- Format 2：`solutions` 与 `challenges` 逐项对齐。`sha256-pow` 使用 `{ "nonce": number|string }`，
  RSW 使用 `{ "y": "<hex>" }`，instrumentation 使用
  `{ "instr": { "i": "...", "state": {...}, "ts": 1700000000001 } }`，或在该项中使用
  `blocked` / `timeout` 信号。

成功 Web 响应字段是 `success=true`、`token`、可空 `tokenKey`、`expires`、可空 `scope`、`iat`，
与 `RedeemResult.Success` accessor 同名。失败 Web 响应字段是 `success=false`、`reason`、
`instr_error`、可空 `error`；adapter 从 `RedeemResult.Failure.instrError()` 显式映射
`instr_error`。当前默认实现不把内部异常消息放入 `error`。

## JWT 字段

challenge token 使用严格 HS256 JWT，header 为 `alg=HS256`、`typ=JWT`。

Format 1 payload：

| 字段 | 含义 |
| --- | --- |
| `n` | 25-byte 随机 nonce 的小写 hex |
| `c` / `s` / `d` | PoW 数量、salt 长度、目标前缀长度 |
| `exp` / `iat` | 毫秒 Unix 时间戳 |
| `sk` | 可选 scope |
| `x` | 可选 JSON extra |
| `ei` | 可选 Format 1 instrumentation 加密元数据 |

Format 2 payload：

| 字段 | 含义 |
| --- | --- |
| `f` | 固定为 `2` |
| `n` | 16-byte 随机 nonce 的小写 hex |
| `exp` / `iat` | 毫秒 Unix 时间戳 |
| `ev` | 加密的逐协议预期结果 |
| `sk` | 可选 scope |
| `x` | 可选 JSON extra |

`scope` 仅在非空时写入 `sk`；兑换传入非空 `expectedScope` 时必须精确相等。

## 协议细节

### Format 1 SHA-256 PoW

salt 和 target 不直接放入 token。客户端按上游 FNV-1a resume 与 xorshift PRNG，从完整 JWT 和
challenge 序号派生它们，然后寻找 JavaScript `Number`，使
`sha256(salt + String(number))` 的小写 hex 以 target 开头。Java API 收到 `BigDecimal` 或
`BigInteger` 时也先模拟 `JSON.parse` 的 binary64 舍入。参数范围为 `1<=c<=1000`、
`1<=s<=256`、`1<=d<=16`。

### Format 2 SHA-256 PoW

每个公开 challenge 包含 hex `salt` 和 hex `target`。兑换接受 JavaScript `Number` 或字符串
nonce；Number 转字符串（包括 exponent overflow 后的正负 Infinity）、大小写 hex 前缀和奇数
nibble 前缀行为与上游保持一致。

### RSW

公开 payload 是十六进制 `N`、`x` 和整数 `t`；solution 是十六进制 `y`。服务端使用持久化的
`RswKeyPair` 通过 CRT mint 预期 `y`，并以固定宽度、常量时间比较方式验证。`RswKeyPair` 的公开
持久化字段使用十进制，`p` / `q` 必须保密。

### Instrumentation

公开 `blob` 是 raw-deflate 后的 base64 JavaScript。服务端认证加密的元数据包含 `id`、
`expectedVals`、`vars`、`blockAutomatedBrowsers`、`expires`。自定义 transformer 是同步受信边界，
能看到完整脚本和 nonce 相关数据，不受 sandbox 或超时保护。

## AES-256-GCM wire

协议严格复现上游 `core/src/crypto.js`：

1. `key = HMAC-SHA256(secret UTF-8 bytes, info UTF-8 bytes)`；
2. Format 1 instrumentation 的 `info` 为 `cap:enc-v1`，Format 2 的 `info` 为 `cap:fmt2-v1`；
3. 使用 12-byte IV 和 16-byte authentication tag；
4. 不设置 GCM AAD；
5. base64url 编码前的 wire 为 `iv || tag || ciphertext`，不是 JCE 默认的
   `iv || ciphertext || tag`。

## 失败 reason

调用方应把 `reason` 当稳定机器码，不把它直接作为面向用户的错误文案。

| 分类 | reason |
| --- | --- |
| 请求/JWT | `invalid_body`、`missing_token`、`missing_solutions`、`invalid_token`、`expired`、`scope_mismatch` |
| solution | `invalid_solutions`、`invalid_solution` |
| instrumentation | `instr_corrupted`、`instr_expired`、`instr_automated_browser`、`instr_timeout`、`instr_missing`、`instr_failed`、`id_mismatch`、`missing_output`、`invalid_state`、`invalid_meta`、`failed_challenge` |
| 统一后处理 | `already_redeemed`、`nonce_store_error`、`token_signer_error` |

instrumentation 类失败在核心 API 中的 `instrError` 为 `true`，Web adapter 输出
`instr_error=true`；其他失败为 `false`。Format 2 已通过 JWT 但缺失或结构损坏的认证元数据统一
映射为 `invalid_token`。

## 防重放存储兼容性

核心模块默认不配置 `NonceConsumer`，与上游未提供 `consumeNonce` 时的无状态行为一致。显式配置
`CaffeineNonceConsumer`、`JdbcNonceConsumer` 或 `LettuceNonceConsumer` 后才启用防重放。三种实现只改变
消费状态保存位置，不改变协议 wire、失败码或 TTL 语义：防重放 key 始终是 challenge JWT 签名的 64 字符
小写十六进制，成功消费后直到 challenge 剩余 TTL 到期都必须拒绝重放。Caffeine 仅适用于单 JVM；多实例
必须共享同一个原子存储。

外部存储失败统一 fail closed 为 `nonce_store_error`，不会回退到本机 Caffeine。数据库迁移、Redis
命令、过期清理和时钟边界见[防重放存储部署指南](replay-storage.md)。

## 真实 widget E2E

可选 E2E 固定 `@cap.js/widget@0.1.56`、`@cap.js/wasm@0.0.7` 和
`playwright@1.52.0`，在仓库外准备精确 npm 环境后运行：

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

默认 `mvn test` / `mvn verify` 只依赖 Java 17，不执行也不 skip `WidgetBrowserIT`，不依赖 Node 或
Chromium。显式 profile 严格校验 `package-lock.json` 中三个包的 version、resolved URL 和 integrity；
缺 package-lock、精确 artifact、Node、Chromium 或 artifact 文件时硬失败，不能静默 skip。
空或非法 `cap.widget.dir`、Node 启动异常、artifact/lock 校验失败和 Chromium 启动失败均输出固定脱敏类别，
并附上述精确版本安装、`npx playwright@1.52.0 install chromium` 和 `-Dcap.widget.dir` 的可执行准备
步骤；固定诊断与 hint 不包含实际本机路径或敏感值。

测试在真实 Chromium 中只加载回环 server 提供的本地 widget/WASM，不访问 CDN；请求经真实 loopback
HTTP 到达 Java `Cap`。Format 1 E2E 场景显式配置 Caffeine 防重放，覆盖浏览器原始 redeem body 重放返回
`already_redeemed`、Format 1 instrumentation 成功、Format 2 RSW 成功，以及 STRICT 自动化拦截。
STRICT 的后端 403 为 `reason/error=instr_automated_browser` 且 `instr_error=true`；固定 widget 的
`solve()` rejection message 为 `instr_automated_browser`，error event code 是其统一映射
`invalid_solution`。STRICT 页面通过 Playwright init script 植入标准自动化标记；按真实 iframe init
时 `documentElement=null` 计算，不依赖 DOM attribute 仍有 11 类稳定命中。默认 generator 从 18 类
检查随机选取 8 类，未命中类只有 7 类，因此任意子集必有命中；不需要重试，也未替换 production
transformer。`instr_blocked` event code 只属于 Format 1 instrumentation 分支。

测试输出不包含 secret、JWT、solution、业务 token 或 tokenKey。
Java server 只在内存保留 challenge 类型、instrumentation 存在性、Format 2 协议名顺序，以及 redeem
的 instrumentation flags、solution 数量与 shape 等脱敏事实，不保存原始 token、redeem body 或
solution。该 HTTP/JSON server 仅验证互操作，
不改变库的职责：生产应用仍须自行提供 Web 框架、JSON databind、认证、CORS、CSRF、实际端点与边界策略。

## Fixture 来源与复核

互操作 fixture 位于 `cap-server/src/test/resources/fixtures/capjs-core-0.1.1/`：

| 文件 | 来源/用途 |
| --- | --- |
| `format1.json` | 上游 `crypto.js` / `prng.js` 固定 payload、PoW、边界与大整数向量 |
| `rsw.json` | 上游 `core/src/rsw.js` 的真实 `buildRswMinter().mint()` |
| `instrumentation.json` | 上游 `generateInstrumentation()` 的脚本、元数据和压缩 blob |
| `format2.json` | 上游 `generateChallenge()` / `validateChallenge()`、加密与 JS Number 向量 |
| `format2-java.json` | Java 确定性生成、由上游 `validateChallenge()` 反向兑换 |

统一生成结果位于子目录 `generated/`，每个 JSON 都含有 `schema`、
`schemaVersion`、`kind` 及 `source` 元数据，对象字段按字典序输出：

| 文件 | 上游 oracle |
| --- | --- |
| `generated/format1.json` | 官方 `generateChallenge()` 与 `validateChallenge()` 的 Format 1 完整往返 |
| `generated/format1-instrumentation.json` | Format 1 instrumentation 正常兑换与 `instr_blocked=true` 完整 oracle |
| `generated/format2.json` | 固定 RSW keypair 的 SHA-256 PoW / RSW / instrumentation 有序兑换与 blocked oracle |
| `generated/number-string-vectors.json` | 指数边界加 492 个确定随机 binary64 位模式的 Node `String(number)` oracle |

生成器只从当前目录的 `node_modules` 加载官方 `capjs-core@0.1.1`，不使用本地
JavaScript 协议重写。它从 `package-lock.json` 读取并验证 npm version、resolved URL 和
integrity，这三者是 fixture 的 artifact 身份；同时计算实际安装的五个协议源文件
SHA-256，并在生成前逐一与代码中固定的官方 npm artifact 解包摘要比较；任一文件被篡改都立即失败。
这些固定 SHA-256 只对应 npm integrity 指定的 artifact 文件。
`semanticReferenceCommit=f9ffadb` 只说明已经对照过的
语义参考，文件摘要不被声称为该 commit 的身份证明。nonce、IV 和
instrumentation 脚本保持真实随机，所以重生不要求逐字节相等；
Java 测试会对每次新生成的 token 执行验签、解密和协议兑换。完整复核命令为：

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
npm init -y
npm install capjs-core@0.1.1
node "$repo/tools/fixtures/test-generate-capjs-core-fixtures.mjs"
node "$repo/tools/fixtures/generate-capjs-core-fixtures.mjs" --output "$tmp/fixtures"
cd "$repo"
mise exec maven -- mvn -pl cap-server -am -Dcap.fixture.dir="$tmp/fixtures" -Dtest='*CompatibilityTest' test
```

`number-string-vectors.json` 的检查也是显式 opt-in：它由上述生成器写入临时目录，再由
`CapjsCoreCompatibilityTest` 用原始 IEEE-754 bits 还原 `double`，与 Node 的最短字符串逐项比较。
随机位模式使用 xorshift64，每一次 shift/xor 后都显式截断回无符号 64 bit；测试额外
断言总数恰为 500、bits 全部唯一以及 8 个边界标签精确存在。

为定位单个旧 fixture 的差异，仍可 checkout 上游 0.1.1，确认 commit 是 `f9ffadb`，
将其根目录设为 `CAP_UPSTREAM`，运行以下精确随机源工具：

```bash
export CAP_UPSTREAM=/path/to/cap
node tools/fixtures/generate-rsw-fixture.mjs --check "$CAP_UPSTREAM"
node tools/fixtures/generate-instrumentation-fixture.mjs --check "$CAP_UPSTREAM"
node tools/fixtures/generate-format2-fixture.mjs --check "$CAP_UPSTREAM"
```

查看重新生成的 instrumentation / Format 2 JSON 时使用 `--print` 替代 `--check`，人工审查后再更新
对应旧 fixture；RSW 工具只提供 `--check`。新的 Format 1 完整生成入口是统一生成器。

额外的 instrumentation JavaScript 语法/执行检查需要 PATH 中的 Node 24，并保持显式 opt-in：

```bash
mise exec maven -- mvn -pl cap-server -am -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test
```

真实 iframe 执行需要开发机已安装 Chromium 和 Playwright，不进入 Maven 默认生命周期。
在允许下载浏览器的独立临时目录中可复现：

```bash
repo=$(pwd)
tmp=$(mktemp -d)
cd "$tmp"
npm init -y
npm install playwright
npx playwright install chromium
node "$repo/tools/fixtures/check-instrumentation-browser.mjs" \
  --fixture "$repo/cap-server/src/test/resources/fixtures/capjs-core-0.1.1/generated/format1-instrumentation.json"
```

该入口先确认 options 和认证 metadata 都设置 `blockAutomatedBrowsers=true`，再在真实
iframe 中执行 raw-deflate 解压后的上游脚本。上游 blocked wire 固定为
`{type:"cap:instr", nonce, result:"", blocked:true}`；检查器对字段集和值做精确断言。
headless Chromium 若返回普通 `result.i` 路径或没有触发 blocked，命令必须失败，不会把普通结果
当作通过。该命令是上游 Format 1 fixture 的独立可选复核，不替代前述 widget E2E；只有实际准备并
运行 Chromium 后才能声称此项通过。

## 从 2.x 迁移

3.0 是 breaking change，不包含旧 API 适配层。`CapManager`、`CapStore`、旧配置/模型类以及
`validateCapToken(...)` 已删除。迁移原则是：

1. 用 `Cap.builder(secret)` 创建单例门面；
2. challenge 可直接序列化 `ChallengeResponse`；redeem 使用显式 DTO/adapter 映射 snake_case；
3. challenge 状态由签名/加密 JWT 携带；默认不防重放，需一次性兑换时显式配置 Caffeine、JDBC、Redis 或其他原子 `NonceConsumer`；
4. 兑换成功后保存 `tokenKey` 与授权上下文，客户端只持有 `token`；
5. 集群实例共享 `secret`、RSW key pair 和原子 nonce 存储。
