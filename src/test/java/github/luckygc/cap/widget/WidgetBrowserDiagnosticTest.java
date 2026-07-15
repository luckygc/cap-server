package github.luckygc.cap.widget;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NullMarked
@DisplayName("Widget E2E 诊断脱敏")
class WidgetBrowserDiagnosticTest {

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
}
