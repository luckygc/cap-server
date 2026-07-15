package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Maven 测试生命周期契约")
class BuildLifecycleContractTest {
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
}
