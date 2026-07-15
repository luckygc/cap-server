package github.luckygc.cap.widget;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import github.luckygc.cap.Cap;
import github.luckygc.cap.CapProfile;
import github.luckygc.cap.CapProtocol;
import github.luckygc.cap.ChallengeResponse;
import github.luckygc.cap.InstrumentationOptions;
import github.luckygc.cap.RedeemRequest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NullMarked
@DisplayName("Widget 与 Java HTTP 后端 E2E")
class WidgetBrowserIT {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration DRIVER_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String SETUP_HINT =
            "Prepare widget E2E: repo=$(pwd); tmp=$(mktemp -d); cd \"$tmp\"; npm init -y; "
                    + "npm install --save-exact @cap.js/widget@0.1.56 @cap.js/wasm@0.0.7 "
                    + "playwright@1.52.0; npx playwright@1.52.0 install chromium; cd \"$repo\"; "
                    + "mise exec maven -- mvn -Pwidget-e2e -Dcap.widget.dir=\"$tmp\" verify";
    private static final Pattern SAFE_DRIVER_DIAGNOSTIC =
            Pattern.compile(
                    "widget-e2e scenario=([a-z0-9_]+) phase=([a-z0-9_]+) "
                            + "category=([a-z0-9_]+) status=failed\\R?");
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
        Path npmRoot = requireNpmRoot(System.getProperty("cap.widget.dir", ""));

        WidgetServer widgetServer;
        try {
            widgetServer = new WidgetServer(npmRoot);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(driverFailure("category=assets"), exception);
        }
        try (WidgetServer server = widgetServer) {
            Process process =
                    startDriver(
                            new ProcessBuilder(
                                    "node",
                                    "tools/widget-e2e/run-widget-e2e.mjs",
                                    "--npm-root",
                                    npmRoot.toAbsolutePath().toString(),
                                    "--base-url",
                                    server.baseUrl()));
            Map<Long, ProcessHandle> observedDescendants = new LinkedHashMap<>();
            try {
                boolean finished = awaitDriver(process, observedDescendants);
                assertThat(finished).as(driverFailure("category=deadline")).isTrue();

                String output =
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String errorOutput =
                        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(driverExitFailure(errorOutput)).isZero();
                assertThat(errorOutput).as("successful driver must not emit stderr").isEmpty();
                assertThat(output.lines()).as("driver must emit one summary line").hasSize(1);
                assertThat(summary(output)).containsExactlyInAnyOrderEntriesOf(EXPECTED_SUMMARY);

                assertSuccessfulScenario(server.scenario("format1"));
                assertSuccessfulScenario(server.scenario("instrumented"));
                assertSuccessfulScenario(server.scenario("format2"));
                assertThat(server.scenario("instrumented").lastChallengeFact())
                        .hasValue(new ChallengeFact("format1", true, List.of()));
                assertThat(server.scenario("instrumented").lastRedeemFact())
                        .hasValue(new RedeemFact(true, false, false, 1, "number"));
                assertThat(server.scenario("format2").lastChallengeFact())
                        .hasValue(new ChallengeFact("format2", false, List.of("rsw")));
                assertThat(server.scenario("format2").lastRedeemFact())
                        .hasValue(new RedeemFact(false, false, false, 1, "rsw_exact"));
                assertThat(server.scenario("format1").redeemCount()).hasValue(2);
                assertThat(server.scenario("format1").lastReason()).hasValue("already_redeemed");
                assertThat(server.scenario("strict").challengeCount()).hasPositiveValue();
                assertThat(server.scenario("strict").redeemCount()).hasPositiveValue();
                assertThat(server.scenario("strict").lastRedeemFact().get()).isNotNull();
                assertThat(server.scenario("strict").lastReason())
                        .hasValue("instr_automated_browser");
            } finally {
                terminate(process, List.copyOf(observedDescendants.values()));
            }
        }
    }

    static Path requireNpmRoot(String value) {
        if (value.isBlank()) {
            throw new IllegalStateException(driverFailure("category=property"));
        }
        try {
            return Path.of(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(driverFailure("category=property"));
        }
    }

    static Process startDriver(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException(driverFailure("category=node_launch"), exception);
        }
    }

    static String driverFailure(String diagnostic) {
        return "widget E2E failed: " + diagnostic + System.lineSeparator() + SETUP_HINT;
    }

    static String driverExitFailure(String errorOutput) {
        return driverFailure(safeDriverDiagnostic(errorOutput));
    }

    static String solutionShape(@Nullable Object solution) {
        if (solution instanceof Number) {
            return "number";
        }
        if (solution instanceof Map<?, ?> map
                && map.size() == 1
                && map.keySet().equals(Set.of("y"))
                && map.get("y") instanceof String) {
            return "rsw_exact";
        }
        return "other";
    }

    private static void assertSuccessfulScenario(Scenario scenario) {
        assertThat(scenario.challengeCount()).hasPositiveValue();
        assertThat(scenario.redeemCount()).hasPositiveValue();
        assertThat(scenario.lastChallengeFact().get()).isNotNull();
        assertThat(scenario.lastRedeemFact().get()).isNotNull();
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

    static String safeDriverDiagnostic(String errorOutput) {
        Matcher matcher = SAFE_DRIVER_DIAGNOSTIC.matcher(errorOutput);
        if (!matcher.matches()) {
            return "diagnostic_unavailable";
        }
        return "scenario="
                + matcher.group(1)
                + " phase="
                + matcher.group(2)
                + " category="
                + matcher.group(3);
    }

    private static boolean awaitDriver(
            Process process, Map<Long, ProcessHandle> observedDescendants)
            throws InterruptedException {
        long deadline = System.nanoTime() + DRIVER_TIMEOUT.toNanos();
        while (process.isAlive()) {
            process.descendants().forEach(handle -> observedDescendants.put(handle.pid(), handle));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            process.waitFor(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
        }
        return true;
    }

    private static void terminate(Process process, List<ProcessHandle> descendants)
            throws InterruptedException {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }

        long deadline = System.nanoTime() + PROCESS_EXIT_TIMEOUT.toNanos();
        while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("widget E2E process did not terminate");
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
        }
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
                ChallengeResponse response = scenario.cap().createChallenge();
                scenario.lastChallengeFact().set(ChallengeFact.from(response));
                sendJson(exchange, 200, adapter.encodeChallenge(response));
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
            RedeemResult result;
            try {
                RedeemRequest request = adapter.decodeRedeem(body);
                scenario.lastRedeemFact().set(RedeemFact.from(request));
                result = scenario.cap().redeem(request);
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
            AtomicReference<@Nullable ChallengeFact> lastChallengeFact,
            AtomicReference<@Nullable RedeemFact> lastRedeemFact) {
        Scenario(Cap cap) {
            this(
                    cap,
                    new AtomicInteger(),
                    new AtomicInteger(),
                    new AtomicReference<>(),
                    new AtomicReference<>(),
                    new AtomicReference<>());
        }
    }

    private record ChallengeFact(
            String type, boolean instrumentationPresent, List<String> protocols) {
        static ChallengeFact from(ChallengeResponse response) {
            if (response instanceof ChallengeResponse.Format1 format1) {
                return new ChallengeFact("format1", format1.instrumentation() != null, List.of());
            }
            ChallengeResponse.Format2 format2 = (ChallengeResponse.Format2) response;
            return new ChallengeFact(
                    "format2",
                    false,
                    format2.challenges().stream()
                            .map(ChallengeResponse.ProtocolChallenge::protocol)
                            .toList());
        }
    }

    private record RedeemFact(
            boolean instrumentationPresent,
            boolean instrumentationBlocked,
            boolean instrumentationTimedOut,
            int solutionCount,
            String firstSolutionShape) {
        static RedeemFact from(RedeemRequest request) {
            String shape = "empty";
            if (!request.solutions().isEmpty()) {
                shape = solutionShape(request.solutions().get(0));
            }
            return new RedeemFact(
                    request.instr() != null,
                    request.instrBlocked(),
                    request.instrTimeout(),
                    request.solutions().size(),
                    shape);
        }
    }
}
