# 防重放存储部署指南

Cap 在兑换成功前以 challenge JWT 签名的小写十六进制作为防重放 key，并按 challenge 的剩余 TTL
原子消费它。不得记录签名、challenge token、solution、业务 token、tokenKey 或存储命令的实际值。

## 选择存储

Caffeine 仅保证单 JVM 内的原子消费，是 `cap-server` 的零配置默认实现。多实例部署必须为所有实例
配置 JDBC、Redis 或其他共享且原子的 `NonceConsumer`。共享存储必须实现“不存在则写入并设置 TTL”；
外部存储失败时兑换 fail closed 为 `nonce_store_error`，不会回退到本机 Caffeine。

`cap-server-jdbc` 的 `JdbcNonceConsumer` 依赖数据库主键唯一约束。传入的 `DataSource` 必须在每次
`getConnection()` 时返回独立、`autoCommit=true`、不绑定宿主事务的连接。consumer 会在该连接上管理
自己的短事务；transaction-aware DataSource 可能把回滚或提交绑定到业务事务，不能使用。

`cap-server-redis` 的 `LettuceNonceConsumer` 使用调用方传入的同步 commands，并执行等价于下列命令的
单次原子写入：

```text
SET key 1 NX PX ttlMillis
```

commands、底层连接或连接池及其超时均由调用方拥有和管理。consumer 不关闭连接，也不配置超时；调用方
必须设置有界的连接与命令超时。

## PostgreSQL 迁移与清理

由宿主应用的 schema migration 执行：

```sql
CREATE TABLE cap_consumed_nonces (
    signature_hex VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX cap_consumed_nonces_expires_at_idx
    ON cap_consumed_nonces (expires_at);
```

由宿主应用的定时任务执行清理：

```sql
DELETE FROM cap_consumed_nonces
WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 minute';
```

## MySQL / MariaDB 迁移与清理

由宿主应用的 schema migration 执行：

```sql
CREATE TABLE cap_consumed_nonces (
    signature_hex VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    expires_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX cap_consumed_nonces_expires_at_idx
    ON cap_consumed_nonces (expires_at);
```

由宿主应用的定时任务执行清理：

```sql
DELETE FROM cap_consumed_nonces
WHERE expires_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE;
```

## 清理与时钟边界

清理条件保留一分钟安全余量，用于吸收应用节点与数据库之间的时钟偏差，避免仍处于 challenge TTL 内的
记录被提前删除。可以根据经过验证的最大时钟偏差增加余量；没有可证明的时钟误差上界时不得移除或缩短
该余量。所有应用节点和数据库都应同步时钟并监控偏差。

MySQL / MariaDB 的 `TIMESTAMP` 会按会话时区转换。连接池初始化时应把会话时区固定为 UTC，并确认 JDBC
驱动、数据库会话与应用写入的 `Instant` 语义一致；若会话时区或时钟不一致，可能提前清理。PostgreSQL
使用带时区时间戳，仍需控制应用与数据库时钟偏差。

表结构变更、索引和定时清理都属于宿主应用的运维职责。本库不会自动建表、修改 schema 或启动清理任务。
