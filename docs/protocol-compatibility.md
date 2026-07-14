# capjs-core 0.1.1 协议兼容性

本文记录 cap-server 3.0 与上游 `capjs-core` 0.1.1（fixture 固定到 commit
`f9ffadb`）之间的 wire 约定。宿主应用负责 HTTP 和 JSON 序列化，并必须显式处理 Web wire 与
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

- Format 1：`solutions` 是与 `c` 等长的整数数组；启用 instrumentation 时，顶层 `instr` 包含
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
challenge 序号派生它们，然后寻找整数 `nonce`，使 `sha256(salt + decimal(nonce))` 的小写 hex
以 target 开头。参数范围为 `1<=c<=1000`、`1<=s<=256`、`1<=d<=16`。

### Format 2 SHA-256 PoW

每个公开 challenge 包含 hex `salt` 和 hex `target`。兑换接受 JavaScript `Number` 或字符串
nonce；Number 转字符串、大小写 hex 前缀和奇数 nibble 前缀行为与上游保持一致。

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

## Fixture 来源与复核

互操作 fixture 位于 `src/test/resources/fixtures/capjs-core-0.1.1/`：

| 文件 | 来源/用途 |
| --- | --- |
| `format1.json` | 上游 `crypto.js` / `prng.js` 固定 payload、PoW、边界与大整数向量 |
| `rsw.json` | 上游 `core/src/rsw.js` 的真实 `buildRswMinter().mint()` |
| `instrumentation.json` | 上游 `generateInstrumentation()` 的脚本、元数据和压缩 blob |
| `format2.json` | 上游 `generateChallenge()` / `validateChallenge()`、加密与 JS Number 向量 |
| `format2-java.json` | Java 确定性生成、由上游 `validateChallenge()` 反向兑换 |

常规 `mvn test` 读取这些静态 fixture，不需要 Node。复核或重生前，checkout 上游 0.1.1 并确认
commit 是 `f9ffadb`，将其根目录设为 `CAP_UPSTREAM`。工具会复制未修改的上游源码到临时目录，
只替换随机源以获得确定结果：

```bash
export CAP_UPSTREAM=/path/to/cap
node tools/fixtures/generate-rsw-fixture.mjs --check "$CAP_UPSTREAM"
node tools/fixtures/generate-instrumentation-fixture.mjs --check "$CAP_UPSTREAM"
node tools/fixtures/generate-format2-fixture.mjs --check "$CAP_UPSTREAM"
```

查看重新生成的 instrumentation / Format 2 JSON 时使用 `--print` 替代 `--check`，人工审查后再更新
对应 fixture；RSW 工具当前只提供 `--check`。Format 1 fixture 的生成来源和固定输入记录在文件内，
当前没有独立生成脚本。

额外的 instrumentation JavaScript 语法/执行检查需要 PATH 中的 Node 24，并保持显式 opt-in：

```bash
mise exec maven -- mvn -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test
```

## 从 2.x 迁移

3.0 是 breaking change，不包含旧 API 适配层。`CapManager`、`CapStore`、旧配置/模型类以及
`validateCapToken(...)` 已删除。迁移原则是：

1. 用 `Cap.builder(secret)` 创建单例门面；
2. challenge 可直接序列化 `ChallengeResponse`；redeem 使用显式 DTO/adapter 映射 snake_case；
3. challenge 状态由签名/加密 JWT 携带，防重放由 Caffeine 或外部原子 `NonceConsumer` 负责；
4. 兑换成功后保存 `tokenKey` 与授权上下文，客户端只持有 `token`；
5. 集群实例共享 `secret`、RSW key pair 和原子 nonce 存储。
