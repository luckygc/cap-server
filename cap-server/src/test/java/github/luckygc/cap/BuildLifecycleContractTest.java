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
