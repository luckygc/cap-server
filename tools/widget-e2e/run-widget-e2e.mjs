import { pathToFileURL } from "node:url";

import { resolveWidgetAssets } from "./widget-assets.mjs";

const SCENARIO_TIMEOUT_MS = 60_000;
export const STRICT_403_CONSOLE_ERROR =
  "Failed to load resource: the server responded with a status of 403 (Forbidden)";

export function isExpectedStrictConsoleError(type, text) {
  return type === "error" && text === STRICT_403_CONSOLE_ERROR;
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
      page = await context.newPage();
      const observation = observePage(page, scenario);
      try {
        await page.goto(`${baseUrl}/?scenario=${scenario}`);
        await page.evaluate(() => customElements.whenDefined("cap-widget"));
        const result = await operation(page);
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
    await page.evaluate(() => document.getElementById("cap").solve());
    await page.waitForFunction(() => window.__capResult !== null);
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
    const solveOutcome = await page.evaluate(async () => {
      try {
        await document.getElementById("cap").solve();
        return { rejected: false, message: "" };
      } catch (error) {
        return { rejected: true, message: error.message };
      }
    });
    await page.waitForFunction(() => window.__capResult !== null);
    const result = await page.evaluate(() => window.__capResult);
    const response = await redeemResponse;
    const body = await response.json();
    check(solveOutcome.rejected, "strict solve did not reject");
    check(
      solveOutcome.message === "instr_automated_browser",
      "strict solve rejection mismatch",
    );
    check(result.type === "error", "strict did not emit error");
    check(result.code === "invalid_solution", "strict emitted wrong error code");
    check(response.status() === 403, "strict redeem did not return 403");
    check(
      body.reason === "instr_automated_browser" && body.instr_error === true,
      "strict backend reason mismatch",
    );
  });
}

async function main() {
  const { npmRoot, baseUrl } = argumentsOf(process.argv.slice(2));
  const assets = await resolveWidgetAssets(npmRoot);
  const { chromium } = await import(pathToFileURL(assets.playwrightModule).href);
  const expectedOrigin = new URL(baseUrl).origin;

  let browser;
  try {
    browser = await chromium.launch({ headless: true });
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
  } finally {
    await browser?.close();
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await main();
}
