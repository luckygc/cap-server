package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Maven 测试生命周期契约")
class BuildLifecycleContractTest {
    @Test
    @DisplayName("widget E2E 仅由显式 Failsafe profile 执行")
    void widgetE2eIsOptIn() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom)
                .contains(
                        "<failsafe.version>3.5.6</failsafe.version>",
                        "<id>widget-e2e</id>",
                        "<artifactId>maven-failsafe-plugin</artifactId>",
                        "<goal>integration-test</goal>",
                        "<goal>verify</goal>");
        assertThat(pom.indexOf("<artifactId>maven-failsafe-plugin</artifactId>"))
                .isGreaterThan(pom.indexOf("<profiles>"));
    }
}
