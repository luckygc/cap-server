package github.luckygc.cap.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NullMarked
@DisplayName("Widget E2E 诊断脱敏")
class WidgetBrowserDiagnosticTest {

    private static final List<String> SETUP_MARKERS =
            List.of(
                    "repo=$(pwd)",
                    "tmp=$(mktemp -d)",
                    "npm install --save-exact @cap.js/widget@0.1.56 @cap.js/wasm@0.0.7 playwright@1.52.0",
                    "npx playwright@1.52.0 install chromium",
                    "-Dcap.widget.dir=\"$tmp\"");

    @Test
    @DisplayName("只接受固定字段的单行失败诊断")
    void acceptsOnlySafeFailureDiagnostic() {
        assertThat(
                        WidgetBrowserIT.safeDriverDiagnostic(
                                "widget-e2e scenario=strict phase=assert_solve "
                                        + "category=assertion_missing status=failed\n"))
                .isEqualTo("scenario=strict phase=assert_solve category=assertion_missing");
        assertThat(WidgetBrowserIT.safeDriverDiagnostic("token=sensitive-value\n"))
                .isEqualTo("diagnostic_unavailable");
        assertThat(WidgetBrowserIT.safeDriverDiagnostic("first\nsecond\n"))
                .isEqualTo("diagnostic_unavailable");
    }

    @Test
    @DisplayName("空 npm 目录给出固定可执行准备步骤")
    void emptyWidgetDirectoryShowsSetupHint() {
        assertThatThrownBy(() -> WidgetBrowserIT.requireNpmRoot(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll(SETUP_MARKERS.toArray(String[]::new))
                .hasMessageNotContaining(System.getProperty("user.home"));
    }

    @Test
    @DisplayName("Node 启动异常给出同一固定准备步骤")
    void nodeLaunchFailureShowsSetupHint() {
        ProcessBuilder missingNode =
                new ProcessBuilder("cap-widget-e2e-node-command-that-does-not-exist");

        assertThatThrownBy(() -> WidgetBrowserIT.startDriver(missingNode))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContainingAll(SETUP_MARKERS.toArray(String[]::new))
                .hasMessageNotContaining(System.getProperty("user.home"));
    }

    @Test
    @DisplayName("artifact 与 Chromium 失败类别均附固定准备步骤")
    void driverExitFailuresShowSetupHint() {
        assertThat(WidgetBrowserIT.driverFailure("category=assets"))
                .contains("category=assets")
                .contains(SETUP_MARKERS);
        assertThat(WidgetBrowserIT.driverFailure("category=browser_launch"))
                .contains("category=browser_launch")
                .contains(SETUP_MARKERS);
    }
}
