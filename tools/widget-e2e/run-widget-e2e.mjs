import { pathToFileURL } from "node:url";

import { resolveWidgetAssets } from "./widget-assets.mjs";

const SCENARIO_TIMEOUT_MS = 60_000;
const diagnostic = { scenario: "driver", phase: "startup" };
let capturedFailure;
const SAFE_SCENARIOS = new Set([
  "driver",
  "format1",
  "replay",
  "instrumented",
  "format2",
  "strict",
]);
const SAFE_PHASES = new Set([
  "startup",
  "arguments",
  "assets",
  "playwright_import",
  "browser_launch",
  "context",
  "new_page",
  "navigate",
  "widget_ready",
  "operation",
  "solve",
  "wait_result",
  "read_result",
  "assert_solve",
  "assert_event",
  "assert_response",
  "frame_flush",
  "request",
  "complete",
  "browser_close",
]);

function mark(scenario, phase) {
  diagnostic.scenario = scenario;
  diagnostic.phase = phase;
}

function errorCategory(failure, error) {
  if (failure.phase === "assets") return "assets";
  if (failure.phase === "browser_launch") return "browser_launch";
  if (error instanceof Error && error.message.endsWith("scenario timed out")) {
    return "deadline";
  }
  if (error instanceof Error && error.message.includes("Target page, context or browser")) {
    return "browser_closed";
  }
  if (error instanceof Error && error.message.includes("net::")) {
    return "navigation";
  }
  if (error instanceof Error && error.message.includes("mismatch")) {
    return "assertion_mismatch";
  }
  if (error instanceof Error && error.message.includes("did not")) {
    return "assertion_missing";
  }
  return "unknown";
}

export function formatFailureDiagnostic(failure, error) {
  const scenario = SAFE_SCENARIOS.has(failure.scenario)
    ? failure.scenario
    : "driver";
  const phase = SAFE_PHASES.has(failure.phase) ? failure.phase : "startup";
  return `widget-e2e scenario=${scenario} phase=${phase} category=${errorCategory(failure, error)} status=failed\n`;
}
export const STRICT_403_CONSOLE_ERROR =
  "Failed to load resource: the server responded with a status of 403 (Forbidden)";

export function isExpectedStrictConsoleError(type, text) {
  return type === "error" && text === STRICT_403_CONSOLE_ERROR;
}

export function installStrictAutomationMarkers(
  targetWindow = window,
  targetNavigator = navigator,
  targetDocument = document,
) {
  Object.defineProperties(targetNavigator, {
    webdriver: { configurable: true, value: true },
    mimeTypes: { configurable: true, value: {} },
    userAgent: { configurable: true, value: "Chrome HeadlessChrome" },
    appVersion: { configurable: true, value: "Chrome HeadlessChrome" },
    productSub: { configurable: true, value: "widget-e2e" },
  });
  Object.assign(targetWindow, {
    cdc_widget_e2e: true,
    _selenium: true,
    exposedFn: function exposeBindingHandle() {},
    fixture_Array: true,
  });
  Object.defineProperties(targetWindow, {
    outerWidth: { configurable: true, value: 0 },
    outerHeight: { configurable: true, value: 0 },
  });
  targetDocument.__webdriver_evaluate = true;
  targetDocument.hasFocus = () => true;
  targetDocument.documentElement?.setAttribute("data-webdriver", "true");
}

export async function withDeadline(
  scenario,
  timeoutMs,
  operation,
  cleanup = async () => {},
) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      void Promise.resolve()
        .then(cleanup)
        .catch(() => {});
      reject(new Error(`${scenario} scenario timed out`));
    }, timeoutMs);
  });
  try {
    return await Promise.race([Promise.resolve().then(operation), timeout]);
  } finally {
    clearTimeout(timer);
  }
}

function argumentsOf(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith("--") || value === undefined || values.has(name)) {
      throw new Error("invalid driver arguments");
    }
    values.set(name, value);
  }
  const npmRoot = values.get("--npm-root");
  const baseUrl = values.get("--base-url");
  if (!npmRoot || !baseUrl || values.size !== 2) {
    throw new Error("required driver argument is missing");
  }
  return { npmRoot, baseUrl };
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

function observePage(page, scenario) {
  const failures = [];
  let strict403Count = 0;
  page.setDefaultTimeout(SCENARIO_TIMEOUT_MS);
  page.on("console", (message) => {
    if (
      scenario === "strict" &&
      isExpectedStrictConsoleError(message.type(), message.text())
    ) {
      strict403Count++;
      if (strict403Count > 1) failures.push("duplicate strict 403 console error");
    } else if (message.type() === "error") {
      failures.push("unexpected browser console error");
    }
  });
  page.on("pageerror", () => failures.push("unexpected page error"));
  page.on("requestfailed", () => failures.push("unexpected request failure"));
  return {
    failures,
    strict403Count: () => strict403Count,
  };
}

async function runPageScenario(context, baseUrl, scenario, operation) {
  let page;
  return withDeadline(
    scenario,
    SCENARIO_TIMEOUT_MS,
    async () => {
      mark(scenario, "new_page");
      page = await context.newPage();
      if (scenario === "strict") {
        await page.addInitScript(installStrictAutomationMarkers);
      }
      const observation = observePage(page, scenario);
      try {
        mark(scenario, "navigate");
        await page.goto(`${baseUrl}/?scenario=${scenario}`);
        mark(scenario, "widget_ready");
        await page.evaluate(() => customElements.whenDefined("cap-widget"));
        mark(scenario, "operation");
        const result = await operation(page);
        mark(scenario, "frame_flush");
        await page.evaluate(
          () => new Promise((resolve) => requestAnimationFrame(() => resolve())),
        );
        check(
          observation.failures.length === 0,
          observation.failures[0] || `${scenario} browser failure`,
        );
        if (scenario === "strict") {
          check(
            observation.strict403Count() === 1,
            "strict 403 console error count mismatch",
          );
        }
        mark(scenario, "complete");
        return result;
      } finally {
        await page.close();
      }
    },
    async () => {
      if (page && !page.isClosed()) await page.close();
    },
  );
}

async function solveScenario(context, baseUrl, scenario) {
  let redeemBody;
  return runPageScenario(context, baseUrl, scenario, async (page) => {
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (request.method() === "POST" && path === `/${scenario}/redeem`) {
        redeemBody = request.postData();
      }
    });
    mark(scenario, "solve");
    await page.evaluate(() => document.getElementById("cap").solve());
    mark(scenario, "wait_result");
    await page.waitForFunction(() => window.__capResult !== null);
    mark(scenario, "read_result");
    const result = await page.evaluate(() => window.__capResult);
    check(result.type === "solve", `${scenario} did not emit solve`);
    check(
      typeof result.token === "string" && result.token.length > 0,
      `${scenario} emitted an empty token`,
    );
    const hiddenToken = await page
      .locator('input[name="cap-token"]')
      .inputValue();
    check(hiddenToken === result.token, `${scenario} hidden token mismatch`);
    check(typeof redeemBody === "string", `${scenario} redeem was not observed`);
    return redeemBody;
  });
}

async function solveStrict(context, baseUrl) {
  return runPageScenario(context, baseUrl, "strict", async (page) => {
    const redeemResponse = page.waitForResponse((response) => {
      const path = new URL(response.url()).pathname;
      return (
        response.request().method() === "POST" && path === "/strict/redeem"
      );
    });
    mark("strict", "solve");
    const solveOutcome = await page.evaluate(async () => {
      try {
        await document.getElementById("cap").solve();
        return { rejected: false, message: "" };
      } catch (error) {
        return { rejected: true, message: error.message };
      }
    });
    mark("strict", "wait_result");
    await page.waitForFunction(() => window.__capResult !== null);
    mark("strict", "read_result");
    const result = await page.evaluate(() => window.__capResult);
    const response = await redeemResponse;
    const body = await response.json();
    mark("strict", "assert_solve");
    check(solveOutcome.rejected, "strict solve did not reject");
    check(
      solveOutcome.message === "instr_automated_browser",
      "strict solve rejection mismatch",
    );
    mark("strict", "assert_event");
    check(result.type === "error", "strict did not emit error");
    check(result.code === "invalid_solution", "strict emitted wrong error code");
    mark("strict", "assert_response");
    check(response.status() === 403, "strict redeem did not return 403");
    check(
      body.reason === "instr_automated_browser" &&
        body.error === body.reason &&
        body.instr_error === true,
      "strict backend reason mismatch",
    );
  });
}

async function main() {
  mark("driver", "arguments");
  const { npmRoot, baseUrl } = argumentsOf(process.argv.slice(2));
  mark("driver", "assets");
  const assets = await resolveWidgetAssets(npmRoot);
  mark("driver", "playwright_import");
  const { chromium } = await import(pathToFileURL(assets.playwrightModule).href);
  const expectedOrigin = new URL(baseUrl).origin;

  let browser;
  try {
    mark("driver", "browser_launch");
    browser = await chromium.launch({ headless: true });
    mark("driver", "context");
    const context = await browser.newContext();
    await context.route("**/*", async (route) => {
      if (new URL(route.request().url()).origin !== expectedOrigin) {
        await route.abort("blockedbyclient");
        return;
      }
      await route.continue();
    });

    const format1Body = await solveScenario(context, baseUrl, "format1");
    const replayController = new AbortController();
    mark("replay", "request");
    await withDeadline(
      "replay",
      SCENARIO_TIMEOUT_MS,
      async () => {
        const replayResponse = await fetch(`${baseUrl}/format1/redeem`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: format1Body,
          signal: replayController.signal,
        });
        const replay = await replayResponse.json();
        check(replayResponse.status === 403, "replay did not return 403");
        check(replay.reason === "already_redeemed", "replay reason mismatch");
      },
      async () => replayController.abort(),
    );
    mark("replay", "complete");

    await solveScenario(context, baseUrl, "instrumented");
    await solveScenario(context, baseUrl, "format2");
    await solveStrict(context, baseUrl);

    process.stdout.write(
      `${JSON.stringify({
        format1: "solved",
        replay: "already_redeemed",
        instrumented: "solved",
        format2: "solved",
        strict: "instr_automated_browser",
      })}\n`,
    );
  } catch (error) {
    capturedFailure = { diagnostic: { ...diagnostic }, error };
    throw error;
  } finally {
    mark("driver", "browser_close");
    await browser?.close();
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    await main();
  } catch (error) {
    process.stderr.write(
      formatFailureDiagnostic(
        capturedFailure?.diagnostic ?? diagnostic,
        capturedFailure?.error ?? error,
      ),
    );
    process.exitCode = 1;
  }
}
