package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Maven 测试生命周期契约")
class BuildLifecycleContractTest {
    @Test
    @DisplayName("本地审查材料被忽略且不进入版本控制")
    void localReviewArtifactsStayUntracked() throws Exception {
        String gitignore = Files.readString(RepositoryPaths.root().resolve(".gitignore"));

        assertThat(gitignore.lines().anyMatch(".superpowers/"::equals))
                .as("repository hygiene ignore rule")
                .isTrue();

        ProcessResult trackedArtifacts = runGit("ls-files", ".superpowers");
        assertThat(trackedArtifacts.exitCode()).as("repository hygiene git command").isZero();
        assertThat(trackedArtifacts.output().isBlank())
                .as("repository hygiene tracked artifacts")
                .isTrue();
    }

    @Test
    @DisplayName("核心坐标由三模块 reactor 保持")
    void reactorPreservesCoreCoordinates() throws Exception {
        String parentPom = Files.readString(RepositoryPaths.root().resolve("pom.xml"));
        assertThat(parentPom)
                .contains(
                        "<artifactId>cap-server-parent</artifactId>",
                        "<packaging>pom</packaging>",
                        "<module>cap-server</module>",
                        "<module>cap-server-jdbc</module>",
                        "<module>cap-server-redis</module>");
        assertThat(Files.readString(RepositoryPaths.root().resolve("cap-server/pom.xml")))
                .contains("<artifactId>cap-server</artifactId>");
    }

    @Test
    @DisplayName("fixture 工具从核心模块读取 RSW 向量")
    void fixtureToolsReadRswFromCoreModule() throws Exception {
        String generator =
                Files.readString(
                        RepositoryPaths.root().resolve("tools/fixtures/generate-rsw-fixture.mjs"));

        assertThat(generator)
                .contains(
                        "\"cap-server/src/test/resources/fixtures/capjs-core-0.1.1/rsw.json\"",
                        "tools/fixtures/generate-rsw-fixture.mjs using core/src/rsw.js "
                                + "buildRswMinter().mint()");
    }

    @Test
    @DisplayName("reactor 文档将核心聚焦测试限定到核心模块")
    void reactorDocumentationTargetsCoreTests() throws Exception {
        String compatibility =
                Files.readString(RepositoryPaths.root().resolve("docs/protocol-compatibility.md"));

        assertThat(compatibility)
                .contains(
                        "mvn -pl cap-server -am -Dcap.fixture.dir=\"$tmp/fixtures\" "
                                + "-Dtest='*CompatibilityTest' test",
                        "mvn -pl cap-server -am -Dcap.nodeChecks=true "
                                + "-Dtest=InstrumentationGeneratorTest test");
    }

    @Test
    @DisplayName("widget E2E 仅由显式 Failsafe profile 执行")
    void widgetE2eIsOptIn() throws Exception {
        String parentPom = Files.readString(RepositoryPaths.root().resolve("pom.xml"));
        String pom = Files.readString(RepositoryPaths.root().resolve("cap-server/pom.xml"));
        assertThat(parentPom).contains("<failsafe.version>3.5.6</failsafe.version>");
        assertThat(pom)
                .contains(
                        "<id>widget-e2e</id>",
                        "<artifactId>maven-failsafe-plugin</artifactId>",
                        "<goal>integration-test</goal>",
                        "<goal>verify</goal>");
        assertThat(pom.indexOf("<artifactId>maven-failsafe-plugin</artifactId>"))
                .isGreaterThan(pom.indexOf("<profiles>"));
    }

    @Test
    @DisplayName("真实存储测试仅由显式 Failsafe profile 执行")
    void storeIntegrationIsOptIn() throws Exception {
        String jdbcPom =
                Files.readString(RepositoryPaths.root().resolve("cap-server-jdbc/pom.xml"));
        String redisPom =
                Files.readString(RepositoryPaths.root().resolve("cap-server-redis/pom.xml"));

        assertThat(jdbcPom)
                .contains(
                        "<id>store-integration</id>",
                        "testcontainers-postgresql",
                        "testcontainers-mysql",
                        "testcontainers-mariadb");
        assertThat(redisPom)
                .contains("<id>store-integration</id>", "<artifactId>testcontainers</artifactId>");
        assertThat(jdbcPom.indexOf("maven-failsafe-plugin"))
                .isGreaterThan(jdbcPom.indexOf("<profiles>"));
        assertThat(redisPom.indexOf("maven-failsafe-plugin"))
                .isGreaterThan(redisPom.indexOf("<profiles>"));
    }

    @Test
    @DisplayName("JDBC 真实存储测试使用各方言最终文档 DDL")
    void jdbcStoreIntegrationUsesDocumentedDialectDdl() throws Exception {
        String testSource =
                Files.readString(
                        RepositoryPaths.root()
                                .resolve(
                                        "cap-server-jdbc/src/test/java/github/luckygc/cap/replay/jdbc/"
                                                + "JdbcNonceConsumerStoreIT.java"));

        assertThat(testSource)
                .contains(
                        "signature_hex VARCHAR(64) PRIMARY KEY",
                        "expires_at TIMESTAMP WITH TIME ZONE NOT NULL",
                        "signature_hex VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY",
                        "expires_at TIMESTAMP(6) NOT NULL",
                        "+ UUID.randomUUID().toString().replace(\"-\", \"\")");
    }

    @Test
    @DisplayName("防重放存储文档覆盖模块、迁移与部署边界")
    void replayStorageDocumentationIsComplete() throws Exception {
        String readme = Files.readString(RepositoryPaths.root().resolve("README.md"));
        String replayStorage =
                Files.readString(RepositoryPaths.root().resolve("docs/replay-storage.md"));
        String compatibility =
                Files.readString(RepositoryPaths.root().resolve("docs/protocol-compatibility.md"));
        String agents = Files.readString(RepositoryPaths.root().resolve("AGENTS.md"));

        assertThat(readme)
                .contains(
                        "cap-server-jdbc",
                        "cap-server-redis",
                        "JdbcNonceConsumer",
                        "LettuceNonceConsumer",
                        "new JdbcNonceConsumer(dataSource, JdbcDialect.POSTGRESQL)",
                        "new LettuceNonceConsumer(connection.sync())");
        assertThat(replayStorage)
                .contains(
                        "cap_consumed_nonces",
                        "PRIMARY KEY",
                        "DELETE FROM cap_consumed_nonces",
                        "SET key 1 NX PX",
                        "Caffeine 仅保证单 JVM",
                        "autoCommit=true",
                        "一分钟安全余量",
                        "会话时区",
                        "CREATE TABLE cap_consumed_nonces (\n"
                                + "    signature_hex VARCHAR(64) PRIMARY KEY,\n"
                                + "    expires_at TIMESTAMP WITH TIME ZONE NOT NULL\n"
                                + ");",
                        "CURRENT_TIMESTAMP - INTERVAL '1 minute'",
                        "CREATE TABLE cap_consumed_nonces (\n"
                                + "    signature_hex VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,\n"
                                + "    expires_at TIMESTAMP(6) NOT NULL\n"
                                + ");",
                        "CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE");
        assertThat(
                        countOccurrences(
                                replayStorage,
                                "CREATE INDEX cap_consumed_nonces_expires_at_idx\n"
                                        + "    ON cap_consumed_nonces (expires_at);"))
                .as("documented expiration indexes")
                .isEqualTo(2);
        assertThat(compatibility)
                .contains("CaffeineNonceConsumer", "JdbcNonceConsumer", "LettuceNonceConsumer");
        assertThat(agents).contains("cap-server-jdbc/", "cap-server-redis/", "-Pstore-integration");
    }

    @Test
    @DisplayName("widget E2E 文档固定依赖与失败语义")
    void widgetE2eDocumentationIsComplete() throws Exception {
        assertWidgetE2eDocumentation("AGENTS.md");
        assertWidgetE2eDocumentation("README.md");
        assertWidgetE2eDocumentation("docs/protocol-compatibility.md");
    }

    private static void assertWidgetE2eDocumentation(String path) throws Exception {
        assertThat(Files.readString(RepositoryPaths.root().resolve(path)))
                .as(path)
                .contains(
                        "-Pwidget-e2e",
                        "-Dcap.widget.dir",
                        "@cap.js/widget@0.1.56",
                        "@cap.js/wasm@0.0.7",
                        "playwright@1.52.0",
                        "instr_automated_browser");
    }

    private static ProcessResult runGit(String... arguments)
            throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process =
                new ProcessBuilder(command)
                        .directory(RepositoryPaths.root().toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(process.waitFor(), output);
    }

    private static int countOccurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private record ProcessResult(int exitCode, String output) {}
}
