# NonceConsumer Three-Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Release 3.0.0 with Caffeine as the single-JVM default and official JDBC and Lettuce implementations for atomic multi-instance replay protection.

**Architecture:** Convert the repository into a parent plus three Maven modules while preserving `com.github.luckygc:cap-server:3.0.0`. The core module owns the stable `NonceConsumer` contract and public Caffeine implementation; optional JDBC and Redis modules implement that contract without changing protocol wire or business-token storage.

**Tech Stack:** Java 17, Maven via `mise`, Caffeine 3.2.4, Lettuce 7.6.0.RELEASE, JDBC/DataSource, Testcontainers 2.0.5, JUnit 6.1.2, AssertJ 3.27.7, Spotless 3.8.0.

## Global Constraints

- Keep all Java source compatible with Java 17; do not use newer language features or APIs.
- Keep `Cap`, `CapBuilder`, option types, extension interfaces, and public records source-, binary-, and wire-compatible unless this plan explicitly moves the previously internal Caffeine class.
- Preserve capjs-core 0.1.1 protocol fields, failure codes, JWT/AES-GCM wire, replay-key derivation, and `token`/`tokenKey` separation.
- Keep `com.github.luckygc:cap-server:3.0.0` as the core artifact coordinate.
- Default `mvn test` and `mvn verify` require only Java 17 and must neither execute nor skip widget/store integration tests.
- Never log or expose secret, challenge token, signature, Redis key, SQL parameter, solution, business token, or tokenKey.
- Never fall back from JDBC or Redis to Caffeine after an external-store failure.
- Use Google Java Format AOSP through Spotless; keep every package `@NullMarked` and mark nullable generic elements precisely.
- Do not deploy Maven artifacts or push the final tag. Create only the local annotated `3.0.0` tag after all required checks pass.

---

### Task 1: Convert the repository to three Maven modules

**Files:**
- Modify: `pom.xml`
- Create: `cap-server/pom.xml`
- Create: `cap-server-jdbc/pom.xml`
- Create: `cap-server-redis/pom.xml`
- Move: `src/main/**` to `cap-server/src/main/**`
- Move: `src/test/**` to `cap-server/src/test/**`
- Create: `cap-server/src/test/java/github/luckygc/cap/RepositoryPaths.java`
- Modify: `cap-server/src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java`
- Modify: `cap-server/src/test/java/github/luckygc/cap/DocumentationWireContractTest.java`
- Modify: `cap-server/src/test/java/github/luckygc/cap/widget/WidgetBrowserIT.java`
- Modify: `tools/fixtures/generate-format2-fixture.mjs`
- Modify: `tools/fixtures/generate-instrumentation-fixture.mjs`
- Modify: `tools/fixtures/generate-capjs-core-fixtures.mjs`
- Modify: `docs/protocol-compatibility.md`

**Interfaces:**
- Consumes: the existing single-module Maven project and its `widget-e2e` profile.
- Produces: reactor modules `cap-server`, `cap-server-jdbc`, and `cap-server-redis`; the core artifact keeps its existing GAV.

- [ ] **Step 1: Record the clean single-module baseline**

Run:

```bash
mise exec maven -- mvn test
```

Expected: the current test suite reports a non-zero test count, zero failures/errors, and no `Tests are skipped.` line.

- [ ] **Step 2: Add a failing reactor-layout contract test**

Before moving the tree, add this method to `BuildLifecycleContractTest`:

```java
@Test
@DisplayName("核心坐标由三模块 reactor 保持")
void reactorPreservesCoreCoordinates() throws Exception {
    String parentPom = Files.readString(Path.of("pom.xml"));
    assertThat(parentPom)
            .contains(
                    "<artifactId>cap-server-parent</artifactId>",
                    "<packaging>pom</packaging>",
                    "<module>cap-server</module>",
                    "<module>cap-server-jdbc</module>",
                    "<module>cap-server-redis</module>");
    assertThat(Files.readString(Path.of("cap-server/pom.xml")))
            .contains("<artifactId>cap-server</artifactId>");
}
```

- [ ] **Step 3: Run the new contract and verify it fails**

Run:

```bash
mise exec maven -- mvn -Dtest=BuildLifecycleContractTest#reactorPreservesCoreCoordinates test
```

Expected: FAIL because `cap-server-parent` and the child POMs do not exist.

- [ ] **Step 4: Move the existing Java tree into the core module**

Run these filesystem moves, preserving Git history:

```bash
mkdir -p cap-server
git mv src cap-server/src
```

Create empty module source roots only when their first Java files are added in later tasks; do not add placeholder classes.

- [ ] **Step 5: Replace the root POM with the aggregator/parent POM**

The root POM must declare:

```xml
<groupId>com.github.luckygc</groupId>
<artifactId>cap-server-parent</artifactId>
<version>3.0.0</version>
<packaging>pom</packaging>

<modules>
    <module>cap-server</module>
    <module>cap-server-jdbc</module>
    <module>cap-server-redis</module>
</modules>
```

Move every existing version property to the parent and add these exact properties:

```xml
<lettuce.version>7.6.0.RELEASE</lettuce.version>
<testcontainers.version>2.0.5</testcontainers.version>
<postgresql.version>42.7.10</postgresql.version>
<mysql.version>9.7.0</mysql.version>
<mariadb.version>3.5.9</mariadb.version>
```

Use `<dependencyManagement>` for all core dependencies, Lettuce, the three JDBC drivers, and the Testcontainers BOM. Keep Spotless bound to `validate`, and keep compiler, Surefire, Failsafe, and Google Java Format versions centrally managed. Do not put the `widget-e2e` execution in the parent because it belongs only to the core module.

- [ ] **Step 6: Create the three child POMs**

Each child must inherit:

```xml
<parent>
    <groupId>com.github.luckygc</groupId>
    <artifactId>cap-server-parent</artifactId>
    <version>3.0.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>
```

`cap-server/pom.xml` declares the current Jackson Core, Caffeine, SLF4J API, JSpecify, JUnit, and AssertJ dependencies and retains the existing `widget-e2e` Failsafe profile. `cap-server-jdbc/pom.xml` declares `cap-server`, JSpecify, JUnit, and AssertJ. `cap-server-redis/pom.xml` declares `cap-server`, Lettuce, JSpecify, JUnit, and AssertJ. Do not add Testcontainers or JDBC drivers outside the explicit integration profile added in Task 5.

- [ ] **Step 7: Make repository-root paths module-safe**

Create `RepositoryPaths.java` as a test-only helper:

```java
package github.luckygc.cap;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryPaths {
    private RepositoryPaths() {}

    static Path root() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("tools"))) {
            return workingDirectory;
        }
        Path parent = workingDirectory.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("tools"))) {
            return parent;
        }
        throw new IllegalStateException("repository root unavailable");
    }
}
```

Update `BuildLifecycleContractTest` and `DocumentationWireContractTest` to resolve root POM, README, AGENTS, and compatibility docs through `RepositoryPaths.root()`. After the move, update the reactor assertion to read the parent with `RepositoryPaths.root().resolve("pom.xml")` and the child POM through `resolve("cap-server/pom.xml")`.

In `WidgetBrowserIT`, add an equivalent private root resolver and pass an absolute normalized path for `tools/widget-e2e/run-widget-e2e.mjs` to `ProcessBuilder`; never include that path in a failure message.

- [ ] **Step 8: Update fixture paths for the module move**

Change only repository paths, not fixture contents or generator labels:

```text
src/test/resources/fixtures/capjs-core-0.1.1/
```

becomes:

```text
cap-server/src/test/resources/fixtures/capjs-core-0.1.1/
```

in the three affected `.mjs` tools and in executable commands in `docs/protocol-compatibility.md`. Keep embedded fixture `generator` provenance strings unchanged because they name the generating tool, not a filesystem target.

- [ ] **Step 9: Run the reactor and focused path contracts**

Run:

```bash
mise exec maven -- mvn -pl cap-server -am -Dtest=BuildLifecycleContractTest,DocumentationWireContractTest,WidgetBrowserDiagnosticTest test
mise exec maven -- mvn test
```

Expected: all three modules build; the focused core tests and the full default suite pass with actual test counts and no skipped-tests banner.

- [ ] **Step 10: Commit the module conversion**

```bash
git add pom.xml cap-server cap-server-jdbc cap-server-redis tools/fixtures docs/protocol-compatibility.md
git commit -m "build: 拆分 nonce consumer 存储模块"
```

---

### Task 2: Make CaffeineNonceConsumer an official public implementation

**Files:**
- Create: `cap-server/src/main/java/github/luckygc/cap/replay/package-info.java`
- Move: `cap-server/src/main/java/github/luckygc/cap/internal/replay/CaffeineNonceConsumer.java` to `cap-server/src/main/java/github/luckygc/cap/replay/CaffeineNonceConsumer.java`
- Delete: `cap-server/src/main/java/github/luckygc/cap/internal/replay/package-info.java`
- Move: `cap-server/src/test/java/github/luckygc/cap/internal/replay/CaffeineNonceConsumerTest.java` to `cap-server/src/test/java/github/luckygc/cap/replay/CaffeineNonceConsumerTest.java`
- Modify: `cap-server/src/main/java/github/luckygc/cap/CapBuilder.java`
- Modify: `cap-server/src/main/java/github/luckygc/cap/internal/DefaultCap.java`
- Modify: `cap-server/src/test/java/github/luckygc/cap/CapApiTest.java`

**Interfaces:**
- Consumes: `NonceConsumer` and the existing hard-capacity Caffeine behavior.
- Produces: public `github.luckygc.cap.replay.CaffeineNonceConsumer()` and `CaffeineNonceConsumer(long maximumSize)`.

- [ ] **Step 1: Write a failing public-API test**

Add to `CapApiTest`:

```java
@Test
@DisplayName("Caffeine nonce consumer 可作为公开 API 构造")
void exposesCaffeineNonceConsumer() throws Exception {
    Class<?> type = Class.forName("github.luckygc.cap.replay.CaffeineNonceConsumer");
    assertThat(type.getConstructor().newInstance()).isInstanceOf(NonceConsumer.class);
    assertThat(type.getConstructor(long.class).newInstance(16L)).isInstanceOf(NonceConsumer.class);
}
```

- [ ] **Step 2: Verify the public API test fails**

Run:

```bash
mise exec maven -- mvn -pl cap-server -Dtest=CapApiTest#exposesCaffeineNonceConsumer test
```

Expected: FAIL with `ClassNotFoundException` for the public replay package.

- [ ] **Step 3: Move the implementation and tests**

Use `git mv`, change both package declarations to `github.luckygc.cap.replay`, and update imports in `CapBuilder` and `DefaultCap`. Create:

```java
@NullMarked
package github.luckygc.cap.replay;

import org.jspecify.annotations.NullMarked;
```

Keep the implementation logic, constructors, constants, TTL behavior, hard capacity, and synchronous concurrency semantics unchanged.

- [ ] **Step 4: Run focused Caffeine and API tests**

Run:

```bash
mise exec maven -- mvn -pl cap-server -Dtest=CapApiTest,CaffeineNonceConsumerTest,CapIntegrationTest test
```

Expected: all focused tests pass; concurrent duplicate consumption still has exactly one success and capacity exhaustion still maps to `nonce_store_error`.

- [ ] **Step 5: Commit the public Caffeine API**

```bash
git add cap-server/src
git commit -m "refactor: 公开 Caffeine nonce consumer"
```

---

### Task 3: Implement the JDBC nonce consumer with independent transactions

**Files:**
- Create: `cap-server-jdbc/src/main/java/github/luckygc/cap/replay/jdbc/package-info.java`
- Create: `cap-server-jdbc/src/main/java/github/luckygc/cap/replay/jdbc/JdbcDialect.java`
- Create: `cap-server-jdbc/src/main/java/github/luckygc/cap/replay/jdbc/JdbcNonceConsumer.java`
- Create: `cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/RecordingJdbc.java`
- Create: `cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/JdbcDialectTest.java`
- Create: `cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/JdbcNonceConsumerTest.java`

**Interfaces:**
- Consumes: `NonceConsumer.consume(String signatureHex, Duration ttl)` and a caller-owned `DataSource` that returns independent, auto-commit connections.
- Produces: `JdbcNonceConsumer(DataSource, JdbcDialect)`, `JdbcNonceConsumer(DataSource, JdbcDialect, String tableName)`, and enum constants `POSTGRESQL`, `MYSQL`, `MARIADB`.

- [ ] **Step 1: Write failing dialect and constructor tests**

Cover these exact cases in `JdbcDialectTest` and `JdbcNonceConsumerTest`:

```java
assertThat(JdbcDialect.POSTGRESQL.isDuplicateKey(new SQLException("duplicate", "23505", 0)))
        .isTrue();
assertThat(JdbcDialect.POSTGRESQL.isDuplicateKey(new SQLException("other", "23000", 1062)))
        .isFalse();
assertThat(JdbcDialect.MYSQL.isDuplicateKey(new SQLException("duplicate", "23000", 1062)))
        .isTrue();
assertThat(JdbcDialect.MARIADB.isDuplicateKey(new SQLException("duplicate", "23000", 1062)))
        .isTrue();
assertThat(JdbcDialect.MYSQL.isDuplicateKey(new SQLException("constraint", "23000", 1048)))
        .isFalse();
```

Also assert that default SQL targets `cap_consumed_nonces`, `tenant_a.cap_consumed_nonces` is accepted, and each of `""`, `"a b"`, `"a;drop"`, `"a--x"`, `"a.\"b\""` is rejected with `IllegalArgumentException` without echoing the invalid value.

- [ ] **Step 2: Verify the JDBC tests fail to compile**

Run:

```bash
mise exec maven -- mvn -pl cap-server-jdbc -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JdbcDialectTest,JdbcNonceConsumerTest test
```

Expected: FAIL because `JdbcDialect` and `JdbcNonceConsumer` do not exist.

- [ ] **Step 3: Implement the dialect enum and package contract**

Create `JdbcDialect` with exact duplicate classification:

```java
public enum JdbcDialect {
    POSTGRESQL {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23505", 0, false);
        }
    },
    MYSQL {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23000", 1062, true);
        }
    },
    MARIADB {
        @Override
        boolean isDuplicateKey(SQLException exception) {
            return hasError(exception, "23000", 1062, true);
        }
    };

    abstract boolean isDuplicateKey(SQLException exception);

    private static boolean hasError(
            SQLException exception, String sqlState, int errorCode, boolean matchErrorCode) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (sqlState.equals(current.getSQLState())
                    && (!matchErrorCode || current.getErrorCode() == errorCode)) {
                return true;
            }
        }
        return false;
    }
}
```

`hasError` must walk `SQLException.getNextException()` so wrapped batch/driver chains are classified, must not inspect or log message text, and for PostgreSQL must match SQLState `23505`; for MySQL/MariaDB it must require both SQLState `23000` and vendor code `1062`.

Add `@NullMarked` package metadata with concise Chinese Javadoc explaining the independent-transaction requirement.

- [ ] **Step 4: Build a deterministic JDBC test double**

`RecordingJdbc` must use JDK dynamic proxies for `DataSource`, `Connection`, and `PreparedStatement` and expose only test observations:

```java
final class RecordingJdbc {
    boolean autoCommit = true;
    boolean committed;
    boolean rolledBack;
    boolean closed;
    @Nullable String sql;
    @Nullable String signature;
    @Nullable Timestamp expiresAt;
    @Nullable SQLException executeFailure;
    @Nullable SQLException commitFailure;
    @Nullable SQLException rollbackFailure;
    @Nullable SQLException restoreFailure;

    DataSource dataSource() {
        return proxy(DataSource.class, this::invokeDataSource);
    }

    private Object invokeDataSource(Method method, @Nullable Object[] arguments) throws Throwable {
        if (method.getName().equals("getConnection")
                && (arguments == null || arguments.length == 0)) {
            return proxy(Connection.class, this::invokeConnection);
        }
        throw new SQLFeatureNotSupportedException(method.getName());
    }

    private Object invokeConnection(Method method, @Nullable Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "getAutoCommit" -> autoCommit;
            case "setAutoCommit" -> setAutoCommit((boolean) Objects.requireNonNull(arguments)[0]);
            case "prepareStatement" -> prepare((String) Objects.requireNonNull(arguments)[0]);
            case "commit" -> commit();
            case "rollback" -> rollback();
            case "close" -> closeConnection();
            case "isClosed" -> closed;
            default -> throw new SQLFeatureNotSupportedException(method.getName());
        };
    }

    private Object invokeStatement(Method method, @Nullable Object[] arguments) throws Throwable {
        return switch (method.getName()) {
            case "setString" -> setSignature((String) Objects.requireNonNull(arguments)[1]);
            case "setTimestamp" -> setExpiresAt((Timestamp) Objects.requireNonNull(arguments)[1]);
            case "executeUpdate" -> execute();
            case "close" -> null;
            default -> throw new SQLFeatureNotSupportedException(method.getName());
        };
    }

    private @Nullable Object setAutoCommit(boolean value) throws SQLException {
        if (value && restoreFailure != null) {
            throw restoreFailure;
        }
        autoCommit = value;
        return null;
    }

    private PreparedStatement prepare(String actualSql) {
        sql = actualSql;
        return proxy(PreparedStatement.class, this::invokeStatement);
    }

    private @Nullable Object commit() throws SQLException {
        if (commitFailure != null) {
            throw commitFailure;
        }
        committed = true;
        return null;
    }

    private @Nullable Object rollback() throws SQLException {
        if (rollbackFailure != null) {
            throw rollbackFailure;
        }
        rolledBack = true;
        return null;
    }

    private @Nullable Object closeConnection() {
        closed = true;
        return null;
    }

    private @Nullable Object setSignature(String value) {
        signature = value;
        return null;
    }

    private @Nullable Object setExpiresAt(Timestamp value) {
        expiresAt = value;
        return null;
    }

    private int execute() throws SQLException {
        if (executeFailure != null) {
            throw executeFailure;
        }
        return 1;
    }

    private static <T> T proxy(Class<T> type, MethodCall call) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (ignored, method, arguments) -> call.invoke(method, arguments)));
    }

    @FunctionalInterface
    private interface MethodCall {
        @Nullable Object invoke(Method method, @Nullable Object[] arguments) throws Throwable;
    }
}
```

Import `java.lang.reflect.Method`, `java.lang.reflect.Proxy`, the JDBC types used above, `Objects`, `DataSource`, and JSpecify `@Nullable`. The proxy must throw `SQLFeatureNotSupportedException` for every unexpected JDBC method instead of returning permissive defaults. This makes each production JDBC call explicit in the tests.

- [ ] **Step 5: Write failing transaction tests**

Using a fixed clock of `2026-07-15T00:00:00Z`, assert:

- a successful insert binds the signature and `2026-07-15T00:00:30Z`, commits once, restores auto-commit, closes, and returns `true`;
- PostgreSQL/MySQL/MariaDB duplicate errors roll back and return `false`;
- an unrelated constraint error rolls back and is rethrown;
- a connection initially in `autoCommit=false` is rejected before preparing SQL;
- commit failure is rethrown after rollback and never returns success;
- rollback failure is suppressed onto the execute/commit failure and prevents a replay result;
- restore failure after commit is thrown, because the connection/store state is uncertain;
- `Duration.ofNanos(1)` stores at least one millisecond and excessive TTL is capped at 24 hours.

Expose a package-private constructor accepting `Clock` solely for deterministic tests; public constructors use `Clock.systemUTC()`.

- [ ] **Step 6: Implement the transaction state machine**

Use this parameterized SQL shape after validating the table name against
`[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*`:

```java
"INSERT INTO " + tableName + " (signature_hex, expires_at) VALUES (?, ?)"
```

The core flow must be equivalent to:

```java
try (Connection connection = dataSource.getConnection()) {
    if (!connection.getAutoCommit()) {
        throw new SQLException("nonce connection must be independent");
    }
    connection.setAutoCommit(false);
    try {
        insert(connection, signatureHex, expiresAt(clock, ttl));
        connection.commit();
        return true;
    } catch (SQLException failure) {
        rollbackOrSuppress(connection, failure);
        if (dialect.isDuplicateKey(failure) && failure.getSuppressed().length == 0) {
            return false;
        }
        throw failure;
    } finally {
        connection.setAutoCommit(true);
    }
}
```

Refine the actual implementation so a restore exception is suppressed onto an existing primary failure rather than replacing it, while a restore exception after an otherwise successful commit is thrown. Validate non-null inputs with `Objects.requireNonNull`. Never include table name, signature, SQL, or parameters in exception messages.

- [ ] **Step 7: Run JDBC tests and full default reactor tests**

Run:

```bash
mise exec maven -- mvn -pl cap-server-jdbc -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JdbcDialectTest,JdbcNonceConsumerTest test
mise exec maven -- mvn test
```

Expected: all JDBC tests and the full reactor suite pass; no database driver or Docker daemon is required.

- [ ] **Step 8: Commit the JDBC implementation**

```bash
git add cap-server-jdbc
git commit -m "feat: 添加 JDBC nonce consumer"
```

---

### Task 4: Implement the Lettuce Redis nonce consumer

**Files:**
- Create: `cap-server-redis/src/main/java/github/luckygc/cap/replay/redis/package-info.java`
- Create: `cap-server-redis/src/main/java/github/luckygc/cap/replay/redis/LettuceNonceConsumer.java`
- Create: `cap-server-redis/src/test/java/github/luckygc/cap/replay/redis/LettuceNonceConsumerTest.java`

**Interfaces:**
- Consumes: caller-owned `io.lettuce.core.api.sync.RedisStringCommands<String, String>`.
- Produces: `LettuceNonceConsumer(RedisStringCommands<String, String>)` and `LettuceNonceConsumer(RedisStringCommands<String, String>, String keyPrefix)` implementing `NonceConsumer`.

- [ ] **Step 1: Write failing Redis behavior tests**

Use a package-private functional seam to capture only key and TTL without mocking the full Lettuce interface:

```java
AtomicReference<String> key = new AtomicReference<>();
AtomicLong ttlMillis = new AtomicLong();
LettuceNonceConsumer consumer =
        new LettuceNonceConsumer(
                (actualKey, actualTtl) -> {
                    key.set(actualKey);
                    ttlMillis.set(actualTtl);
                    return "OK";
                },
                "cap:nonce:");

assertThat(consumer.consume("abc", Duration.ofNanos(1))).isTrue();
assertThat(key).hasValue("cap:nonce:abc");
assertThat(ttlMillis).hasValue(1L);
```

Add tests asserting null reply returns `false`, unexpected non-null reply throws `IllegalStateException` without including the reply/key, `Duration.ofMillis(1500)` uses `1500`, TTL above 24 hours is capped, default/custom prefixes work, empty prefix is rejected, and command exceptions propagate unchanged.

- [ ] **Step 2: Verify Redis tests fail to compile**

Run:

```bash
mise exec maven -- mvn -pl cap-server-redis -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LettuceNonceConsumerTest test
```

Expected: FAIL because `LettuceNonceConsumer` does not exist.

- [ ] **Step 3: Implement the consumer and Lettuce adapter**

Use an internal functional interface:

```java
@FunctionalInterface
interface SetNxPxCommand {
    @Nullable String set(String key, long ttlMillis);
}
```

Public constructors adapt Lettuce exactly once:

```java
this(
        (key, ttlMillis) ->
                commands.set(key, "1", SetArgs.Builder.nx().px(ttlMillis)),
        keyPrefix);
```

`consume` must concatenate prefix and signature, ceil positive sub-millisecond durations to 1ms, cap at 24 hours, return `true` only for exact `"OK"`, return `false` only for null, and throw for every other response. It must not retain or close a connection object and must not catch Lettuce timeout/connection exceptions.

Add `@NullMarked` package metadata and Chinese Javadoc stating that the caller owns commands, connection lifecycle, pooling, and timeout configuration.

- [ ] **Step 4: Run Redis and reactor tests**

Run:

```bash
mise exec maven -- mvn -pl cap-server-redis -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LettuceNonceConsumerTest test
mise exec maven -- mvn test
```

Expected: Redis unit tests and the full default reactor pass without contacting Redis or Docker.

- [ ] **Step 5: Inspect runtime dependency boundaries**

Run:

```bash
mise exec maven -- mvn -pl cap-server dependency:tree
mise exec maven -- mvn -pl cap-server-jdbc dependency:tree
mise exec maven -- mvn -pl cap-server-redis dependency:tree
```

Expected: core has no Lettuce/JDBC driver/Testcontainers dependency; JDBC has no driver/ORM/pool dependency; only Redis carries Lettuce and its runtime graph.

- [ ] **Step 6: Commit the Redis implementation**

```bash
git add cap-server-redis pom.xml
git commit -m "feat: 添加 Lettuce nonce consumer"
```

---

### Task 5: Add opt-in real-store integration tests

**Files:**
- Modify: `pom.xml`
- Modify: `cap-server-jdbc/pom.xml`
- Modify: `cap-server-redis/pom.xml`
- Create: `cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/JdbcNonceConsumerStoreIT.java`
- Create: `cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/StoreIntegrationDataSource.java`
- Create: `cap-server-redis/src/test/java/github/luckygc/cap/replay/redis/LettuceNonceConsumerStoreIT.java`
- Create: `cap-server-redis/src/test/java/github/luckygc/cap/replay/redis/StoreIntegrationSupport.java`
- Modify: `cap-server/src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java`

**Interfaces:**
- Consumes: JDBC and Redis consumers from Tasks 3–4 and Docker only when `-Pstore-integration` is explicit.
- Produces: real PostgreSQL 17.10, MySQL 8.4.10, MariaDB 11.4.12, and Redis 8.2.7 compatibility evidence.

- [ ] **Step 1: Add a failing lifecycle contract**

Extend `BuildLifecycleContractTest` to read both store-module POMs and assert:

```java
assertThat(jdbcPom)
        .contains("<id>store-integration</id>", "testcontainers-postgresql", "testcontainers-mysql", "testcontainers-mariadb");
assertThat(redisPom)
        .contains("<id>store-integration</id>", "<artifactId>testcontainers</artifactId>");
assertThat(jdbcPom.indexOf("maven-failsafe-plugin")).isGreaterThan(jdbcPom.indexOf("<profiles>"));
assertThat(redisPom.indexOf("maven-failsafe-plugin")).isGreaterThan(redisPom.indexOf("<profiles>"));
```

- [ ] **Step 2: Verify the profile contract fails**

Run:

```bash
mise exec maven -- mvn -pl cap-server -Dtest=BuildLifecycleContractTest test
```

Expected: FAIL because neither store module has the profile.

- [ ] **Step 3: Add exact integration dependencies and Failsafe profiles**

Import `org.testcontainers:testcontainers-bom:2.0.5` in parent dependency management. Under a `store-integration` profile in `cap-server-jdbc`, add `testcontainers-postgresql`, `testcontainers-mysql`, `testcontainers-mariadb`, PostgreSQL driver `42.7.10`, MySQL driver `9.7.0`, and MariaDB driver `3.5.9`, all test-scoped. Under the same profile in `cap-server-redis`, add `org.testcontainers:testcontainers:2.0.5` test-scoped.

In both module profiles, bind Failsafe `integration-test` and `verify` and include only:

```xml
<includes>
    <include>**/*StoreIT.java</include>
</includes>
```

Do not activate the profile by file, environment, property default, or Docker availability.

- [ ] **Step 4: Write real JDBC integration tests**

Use Testcontainers JDBC URLs with exact image tags and `TC_DAEMON=true`:

```text
jdbc:tc:postgresql:17.10-alpine:///cap?TC_DAEMON=true
jdbc:tc:mysql:8.4.10:///cap?TC_DAEMON=true
jdbc:tc:mariadb:11.4.12:///cap?TC_DAEMON=true
```

`StoreIntegrationDataSource` must implement only `getConnection()` and `getConnection(user,password)` through `DriverManager`; unsupported DataSource methods throw `SQLFeatureNotSupportedException`.

For each dialect, create the documented table, launch 32 concurrent `consume` calls with distinct connections, and assert exactly one `true`. Then assert a second call returns `false`, a different signature succeeds, and an unrelated NOT NULL/CHECK failure propagates instead of becoming replay. Wrap initial container acquisition failure as:

```java
throw new IllegalStateException(
        "store-integration category=docker_unavailable; start Docker and rerun "
                + "mise exec maven -- mvn -Pstore-integration verify");
```

Do not attach the original cause or include JDBC URLs, credentials, Docker socket paths, or signatures.

- [ ] **Step 5: Write the real Redis integration test**

Start `redis:8.2.7-alpine` with `GenericContainer`, connect with a caller-owned `RedisClient` and `StatefulRedisConnection`, and pass `connection.sync()` into `LettuceNonceConsumer`. Run 32 concurrent calls against one signature and assert exactly one success. Read `pttl` only for the generated test key and assert it is positive and no greater than the requested TTL; after a short bounded TTL expires, assert the same synthetic signature can be consumed again.

Close connection/client/container in that order. Convert startup failure to the same fixed `docker_unavailable` hint without retaining a cause or sensitive path.

- [ ] **Step 6: Prove default lifecycle remains Docker-free**

Run with Docker stopped or without consulting Docker state:

```bash
mise exec maven -- mvn test
mise exec maven -- mvn verify
```

Expected: both pass, report actual unit-test counts, contain no `Tests are skipped.`, do not run `*StoreIT`, and do not print Testcontainers Docker discovery output.

- [ ] **Step 7: Run the explicit integration profile**

Run:

```bash
mise exec maven -- mvn -Pstore-integration verify
```

Expected with Docker: PostgreSQL, MySQL, MariaDB, and Redis StoreIT tests all pass. Expected without Docker: build fails with only the fixed `category=docker_unavailable` preparation hint from the tests; record the unavailable-environment risk in the eventual handoff.

- [ ] **Step 8: Commit the integration profile and tests**

```bash
git add pom.xml cap-server/pom.xml cap-server-jdbc cap-server-redis
git commit -m "test: 验证共享 nonce 存储互操作"
```

---

### Task 6: Document storage choice, migrations, and module layout

**Files:**
- Modify: `README.md`
- Create: `docs/replay-storage.md`
- Modify: `docs/protocol-compatibility.md`
- Modify: `AGENTS.md`
- Modify: `cap-server/src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java`

**Interfaces:**
- Consumes: final artifact names, public constructors, table/key defaults, and integration commands.
- Produces: executable setup guidance for single-JVM Caffeine and clustered JDBC/Redis deployments.

- [ ] **Step 1: Write failing documentation contract assertions**

Add a test that requires README, `docs/replay-storage.md`, protocol compatibility, and AGENTS to contain the relevant markers:

```java
assertThat(readme)
        .contains("cap-server-jdbc", "cap-server-redis", "JdbcNonceConsumer", "LettuceNonceConsumer");
assertThat(replayStorage)
        .contains(
                "cap_consumed_nonces",
                "PRIMARY KEY",
                "DELETE FROM cap_consumed_nonces",
                "SET key 1 NX PX",
                "Caffeine 仅保证单 JVM");
assertThat(agents)
        .contains("cap-server-jdbc/", "cap-server-redis/", "-Pstore-integration");
```

- [ ] **Step 2: Verify the documentation contract fails**

Run:

```bash
mise exec maven -- mvn -pl cap-server -Dtest=BuildLifecycleContractTest test
```

Expected: FAIL because the new artifacts and replay-storage guide are not documented.

- [ ] **Step 3: Update README dependencies and examples**

Keep the quick-start core dependency at `com.github.luckygc:cap-server:3.0.0`. Add separate Maven snippets for `cap-server-jdbc` and `cap-server-redis`, and compiling Java examples for:

```java
new JdbcNonceConsumer(dataSource, JdbcDialect.POSTGRESQL)
new LettuceNonceConsumer(connection.sync())
```

State that Caffeine is the zero-configuration single-JVM default; multi-instance deployments must select JDBC, Redis, or another shared atomic `NonceConsumer`; external failure is fail-closed and never falls back locally. State that JDBC needs an independent non-transaction-aware `DataSource` connection and that the caller owns Lettuce timeouts and connection lifecycle.

- [ ] **Step 4: Write exact migration and cleanup SQL**

Create `docs/replay-storage.md` with separate executable blocks.

PostgreSQL:

```sql
CREATE TABLE cap_consumed_nonces (
    signature_hex VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX cap_consumed_nonces_expires_at_idx
    ON cap_consumed_nonces (expires_at);
DELETE FROM cap_consumed_nonces
WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 minute';
```

MySQL/MariaDB:

```sql
CREATE TABLE cap_consumed_nonces (
    signature_hex VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    expires_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX cap_consumed_nonces_expires_at_idx
    ON cap_consumed_nonces (expires_at);
DELETE FROM cap_consumed_nonces
WHERE expires_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE;
```

Explain that the one-minute safety margin protects against application/database clock skew and can be increased but not removed without a proven clock bound. Explain that schema changes and scheduled cleanup belong to the host application.

- [ ] **Step 5: Update compatibility and contributor guidance**

Update `docs/protocol-compatibility.md` to name all three implementations while stating that signature-hex replay keys and TTL semantics are unchanged. Update `AGENTS.md` module paths, focused Maven commands, default Java-only lifecycle, explicit Docker profile, dependency boundaries, and the rule that Caffeine supports only one JVM. Remove obsolete root `src/` paths.

- [ ] **Step 6: Run documentation and formatting contracts**

Run:

```bash
mise exec maven -- mvn -pl cap-server -Dtest=BuildLifecycleContractTest,DocumentationWireContractTest test
mise exec maven -- mvn spotless:apply
mise exec maven -- mvn spotless:check
```

Expected: documentation snippets/contracts pass, and all Java in all three modules is formatted.

- [ ] **Step 7: Commit documentation**

```bash
git add README.md docs AGENTS.md cap-server/src/test/java/github/luckygc/cap/BuildLifecycleContractTest.java
git commit -m "docs: 说明 nonce 存储与集群部署"
```

---

### Task 7: Perform final compatibility verification and create the 3.0.0 tag

**Files:**
- Verify: all tracked files
- Create: local annotated Git tag `3.0.0`

**Interfaces:**
- Consumes: completed reactor, implementations, tests, and docs from Tasks 1–6.
- Produces: a verified final commit referenced by local annotated tag `3.0.0`.

- [ ] **Step 1: Audit scope and forbidden compatibility regressions**

Run:

```bash
git status --short
rg -n "CapManager|CapStore|MemoryCapStore|validateCapToken" cap-server cap-server-jdbc cap-server-redis README.md docs AGENTS.md
rg -n "lettuce|postgresql|mysql-connector|mariadb-java-client|testcontainers" cap-server/pom.xml
```

Expected: only intentional work is present; removed 2.x API names appear only in migration/compatibility statements; the core POM has no Redis, JDBC driver, or Testcontainers dependencies.

- [ ] **Step 2: Run mandatory formatting, tests, packaging, and whitespace checks**

Run separately and retain the Maven summaries:

```bash
mise exec maven -- mvn spotless:check
mise exec maven -- mvn test
mise exec maven -- mvn verify
git diff --check
```

Expected: every command exits 0; Maven prints non-zero test counts, zero failures/errors, no `Tests are skipped.`, and builds all three JARs.

- [ ] **Step 3: Run opt-in protocol checks affected by moved paths**

If Node 24 is available, run:

```bash
mise exec maven -- mvn -pl cap-server -Dcap.nodeChecks=true -Dtest=InstrumentationGeneratorTest test
node tools/fixtures/test-generate-capjs-core-fixtures.mjs
```

Expected: JavaScript syntax/semantics and fixture-tool self-tests pass. If Node 24 is unavailable, record both commands and the unverified path-migration risk in the handoff.

- [ ] **Step 4: Run real-store integration verification when Docker is available**

Run:

```bash
mise exec maven -- mvn -Pstore-integration verify
```

Expected with Docker: all four stores report passing StoreIT tests. Without Docker: retain the fixed failure category and explicitly report that real-store interoperability was not locally verified; do not claim it passed.

- [ ] **Step 5: Review the final diff before tagging**

Run:

```bash
git status --short
git log --oneline --decorate -n 10
git diff da5fad2..HEAD --stat
git diff da5fad2..HEAD --check
```

Expected: working tree is clean, the planned implementation commits are visible, and no whitespace errors exist. If implementation required a final correction, commit it with a specific message and rerun Steps 1–5 before tagging.

- [ ] **Step 6: Create and verify the local annotated tag**

First confirm no tag already exists:

```bash
git tag --list 3.0.0
```

Expected: no output. Then create and inspect:

```bash
git tag -a 3.0.0 -m "Release 3.0.0"
git show --no-patch --decorate 3.0.0
git rev-parse 3.0.0^{}
git rev-parse HEAD
```

Expected: `3.0.0^{}` and `HEAD` print the same commit ID, and `git show` identifies an annotated tag. Do not push the tag and do not deploy Maven artifacts.

- [ ] **Step 7: Deliver the verification evidence**

Report the three artifact coordinates, the final commit and tag, exact Maven test totals from the successful runs, and any Node/Docker command that could not run with its remaining risk. Explicitly state that the tag is local and has not been pushed.

## Version-selection references

- Lettuce 7.x supports synchronous thread-safe command use and Java 17 is within its supported JVM range: <https://redis.github.io/lettuce/>.
- Testcontainers 2.0.5 and its per-database Maven modules are documented at <https://java.testcontainers.org/>.
- Dependency versions are pinned from Maven Central metadata; container tags are exact official-image tags rather than floating `latest`, major, or minor tags.
