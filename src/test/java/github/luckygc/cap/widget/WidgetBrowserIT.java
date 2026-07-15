package github.luckygc.cap.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import github.luckygc.cap.Cap;
import github.luckygc.cap.CapProfile;
import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemResult;
import github.luckygc.cap.RswKeyPair;
import github.luckygc.cap.internal.json.ProtocolJsonCodec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NullMarked
@DisplayName("Widget 与 Java HTTP 后端 E2E")
class WidgetBrowserIT {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration DRIVER_TIMEOUT = Duration.ofMinutes(3);
    private static final Map<String, String> EXPECTED_SUMMARY =
            Map.of(
                    "format1", "solved",
                    "replay", "already_redeemed",
                    "instrumented", "solved",
                    "format2", "solved",
                    "strict", "instr_automated_browser");

    @Test
    @DisplayName("真实 Chromium 覆盖协议成功、重放和自动化拦截")
    void widgetCallsJavaBackendOverHttp() throws Exception {
        String npmRoot = System.getProperty("cap.widget.dir", "");
        assertThat(npmRoot).as("cap.widget.dir must be non-empty").isNotBlank();

        try (WidgetServer server = new WidgetServer(Path.of(npmRoot))) {
            Process process =
                    new ProcessBuilder(
                                    "node",
                                    "tools/widget-e2e/run-widget-e2e.mjs",
                                    "--npm-root",
                                    Path.of(npmRoot).toAbsolutePath().toString(),
                                    "--base-url",
                                    server.baseUrl())
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            boolean finished = process.waitFor(DRIVER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroy(process);
            }
            assertThat(finished).as("widget E2E driver timed out").isTrue();
            assertThat(process.exitValue()).as("widget E2E driver failed").isZero();

            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(output.lines()).as("driver must emit one summary line").hasSize(1);
            assertThat(summary(output)).containsExactlyInAnyOrderEntriesOf(EXPECTED_SUMMARY);

            assertSuccessfulScenario(server.scenario("format1"));
            assertSuccessfulScenario(server.scenario("instrumented"));
            assertSuccessfulScenario(server.scenario("format2"));
            assertThat(server.scenario("format1").redeemCount()).hasValue(2);
            assertThat(server.scenario("format1").lastReason()).hasValue("already_redeemed");
            assertThat(server.scenario("strict").challengeCount()).hasPositiveValue();
            assertThat(server.scenario("strict").redeemCount()).hasPositiveValue();
            assertThat(server.scenario("strict").lastRedeem().get()).isNotEmpty();
            assertThat(server.scenario("strict").lastReason()).hasValue("instr_automated_browser");
        }
    }

    private static void assertSuccessfulScenario(Scenario scenario) {
        assertThat(scenario.challengeCount()).hasPositiveValue();
        assertThat(scenario.redeemCount()).hasPositiveValue();
        assertThat(scenario.lastRedeem().get()).isNotEmpty();
    }

    private static Map<String, String> summary(String output) {
        Map<String, @Nullable Object> wire =
                new ProtocolJsonCodec().readObject(output.trim().getBytes(StandardCharsets.UTF_8));
        Map<String, String> summary = new LinkedHashMap<>();
        wire.forEach(
                (key, value) -> {
                    if (!(value instanceof String string)) {
                        throw new IllegalArgumentException("driver summary value is invalid");
                    }
                    summary.put(key, string);
                });
        return summary;
    }

    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static final class WidgetServer implements AutoCloseable {
        private static final byte[] INVALID_BODY =
                "{\"success\":false,\"reason\":\"invalid_body\",\"error\":\"invalid_body\"}"
                        .getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final WidgetWireAdapter adapter = new WidgetWireAdapter();
        private final byte[] widgetScript;
        private final byte[] wasm;
        private final Map<String, Scenario> scenarios;

        WidgetServer(Path npmRoot) throws IOException {
            Path absoluteRoot = npmRoot.toAbsolutePath();
            widgetScript =
                    readArtifact(absoluteRoot.resolve("node_modules/@cap.js/widget/cap.min.js"));
            wasm =
                    readArtifact(
                            absoluteRoot.resolve(
                                    "node_modules/@cap.js/wasm/browser/cap_wasm_bg.wasm"));
            RswKeyPair testKey = RswKeyPair.generate(1024);
            scenarios = createScenarios(testKey);
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private static byte[] readArtifact(Path path) {
            try {
                return Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new IllegalStateException("widget E2E artifact is missing");
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        Scenario scenario(String name) {
            return scenarios.get(name);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static Map<String, Scenario> createScenarios(RswKeyPair testKey) {
            Map<String, Scenario> scenarios = new LinkedHashMap<>();
            scenarios.put(
                    "format1",
                    new Scenario(
                            Cap.builder("widget-e2e-format1-secret").format1(2, 8, 2).build()));
            scenarios.put(
                    "instrumented",
                    new Scenario(
                            Cap.builder("widget-e2e-instrumented-secret")
                                    .format1(1, 4, 1)
                                    .instrumentation(
                                            InstrumentationOptions.builder()
                                                    .blockAutomatedBrowsers(false)
                                                    .build())
                                    .build()));
            scenarios.put(
                    "format2",
                    new Scenario(
                            Cap.builder("widget-e2e-format2-secret")
                                    .profile(CapProfile.STRICT)
                                    .protocols(CapProtocol.RSW)
                                    .rswKeyPair(testKey)
                                    .rswIterations(1000)
                                    .build()));
            scenarios.put(
                    "strict",
                    new Scenario(
                            Cap.builder("widget-e2e-strict-secret")
                                    .profile(CapProfile.STRICT)
                                    .rswKeyPair(testKey)
                                    .rswIterations(1000)
                                    .build()));
            return Map.copyOf(scenarios);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                    servePage(exchange);
                    return;
                }
                if (path.equals("/widget.js")) {
                    serveAsset(exchange, widgetScript, "text/javascript; charset=utf-8");
                    return;
                }
                if (path.equals("/cap_wasm_bg.wasm")) {
                    serveAsset(exchange, wasm, "application/wasm");
                    return;
                }
                handleApi(exchange, path);
            }
        }

        private void servePage(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            String name = query != null && query.startsWith("scenario=") ? query.substring(9) : "";
            if (!scenarios.containsKey(name)) {
                sendEmpty(exchange, 404);
                return;
            }
            byte[] body = html(name).getBytes(StandardCharsets.UTF_8);
            send(exchange, 200, "text/html; charset=utf-8", body);
        }

        private void serveAsset(HttpExchange exchange, byte[] body, String contentType)
                throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendEmpty(exchange, 405);
                return;
            }
            send(exchange, 200, contentType, body);
        }

        private void handleApi(HttpExchange exchange, String path) throws IOException {
            String[] segments = path.split("/", -1);
            if (segments.length != 3 || !exchange.getRequestMethod().equals("POST")) {
                sendEmpty(exchange, segments.length == 3 ? 405 : 404);
                return;
            }
            Scenario scenario = scenarios.get(segments[1]);
            if (scenario == null) {
                sendEmpty(exchange, 404);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                sendEmpty(exchange, 413);
                return;
            }
            if (segments[2].equals("challenge")) {
                scenario.challengeCount().incrementAndGet();
                sendJson(exchange, 200, adapter.encodeChallenge(scenario.cap().createChallenge()));
                return;
            }
            if (!segments[2].equals("redeem")) {
                sendEmpty(exchange, 404);
                return;
            }
            redeem(exchange, scenario, body);
        }

        private void redeem(HttpExchange exchange, Scenario scenario, byte[] body)
                throws IOException {
            scenario.redeemCount().incrementAndGet();
            scenario.lastRedeem().set(body.clone());
            RedeemResult result;
            try {
                result = scenario.cap().redeem(adapter.decodeRedeem(body));
            } catch (RuntimeException exception) {
                sendJson(exchange, 400, INVALID_BODY);
                return;
            }
            if (result instanceof RedeemResult.Failure failure) {
                scenario.lastReason().set(failure.reason());
            }
            sendJson(exchange, result.success() ? 200 : 403, adapter.encodeResult(result));
        }

        private static String html(String scenario) {
            return """
                    <!doctype html>
                    <html><head><meta charset="utf-8">
                    <script>
                    window.CAP_SILENT = true;
                    window.CAP_CUSTOM_WASM_URL = "/cap_wasm_bg.wasm";
                    window.__capResult = null;
                    </script>
                    <script src="/widget.js"></script></head><body>
                    <cap-widget id="cap" data-cap-api-endpoint="/%s/" data-cap-worker-count="1"></cap-widget>
                    <script>
                    const cap = document.getElementById("cap");
                    cap.addEventListener("solve", event => {
                      window.__capResult = { type: "solve", token: event.detail.token };
                    });
                    cap.addEventListener("error", event => {
                      window.__capResult = { type: "error", code: event.detail.code };
                    });
                    </script></body></html>
                    """
                    .formatted(scenario);
        }

        private static void sendJson(HttpExchange exchange, int status, byte[] body)
                throws IOException {
            send(exchange, status, "application/json; charset=utf-8", body);
        }

        private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }

        private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private record Scenario(
            Cap cap,
            AtomicInteger challengeCount,
            AtomicInteger redeemCount,
            AtomicReference<@Nullable String> lastReason,
            AtomicReference<byte @Nullable []> lastRedeem) {
        Scenario(Cap cap) {
            this(
                    cap,
                    new AtomicInteger(),
                    new AtomicInteger(),
                    new AtomicReference<>(),
                    new AtomicReference<>());
        }
    }
}
