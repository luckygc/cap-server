package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("接入文档 Web wire 契约测试")
class DocumentationWireContractTest {

    @Test
    @DisplayName("README 显式适配上游 snake_case 与 Java camelCase")
    void readmeDocumentsWireAdapterBoundary() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String compatibility = Files.readString(Path.of("docs/protocol-compatibility.md"));

        assertThat(readme)
                .contains(
                        "record RedeemWireRequest(",
                        "boolean instr_blocked",
                        "boolean instr_timeout",
                        "record Failure(",
                        "boolean instr_error",
                        "failure.instrError()")
                .doesNotContain("records 直接作为 Web DTO");
        assertThat(compatibility)
                .contains(
                        "Web wire 使用 snake_case",
                        "Java API 使用 camelCase",
                        "`instr_blocked`",
                        "`instr_timeout`",
                        "`instr_error`");
    }
}
