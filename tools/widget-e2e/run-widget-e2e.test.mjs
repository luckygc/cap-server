import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  STRICT_403_CONSOLE_ERROR,
  formatFailureDiagnostic,
  installStrictAutomationMarkers,
  isExpectedStrictConsoleError,
  withDeadline,
} from "./run-widget-e2e.mjs";
import { EXPECTED_PACKAGES } from "./widget-assets.mjs";

const DRIVER = fileURLToPath(new URL("./run-widget-e2e.mjs", import.meta.url));

async function runDriver(root) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      process.execPath,
      [
        DRIVER,
        "--npm-root",
        root,
        "--base-url",
        "http://127.0.0.1:9",
      ],
      { stdio: ["ignore", "pipe", "pipe"] },
    );
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.setEncoding("utf8").on("data", (chunk) => {
      stderr += chunk;
    });
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal, stdout, stderr }));
  });
}

async function createDriverRoot(t, playwrightModule) {
  const root = await mkdtemp(join(tmpdir(), "cap-widget-driver-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(
    join(root, "package-lock.json"),
    JSON.stringify({ packages: EXPECTED_PACKAGES }),
  );
  const artifacts = new Map([
    ["node_modules/@cap.js/widget/cap.min.js", ""],
    ["node_modules/@cap.js/wasm/browser/cap_wasm_bg.wasm", ""],
    ["node_modules/playwright/index.mjs", playwrightModule],
  ]);
  for (const [artifact, contents] of artifacts) {
    const path = join(root, artifact);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, contents);
  }
  return root;
}

test("failure diagnostic emits only fixed safe fields", () => {
  const diagnostic = formatFailureDiagnostic(
    { scenario: "strict", phase: "assert_solve" },
    new Error("strict solve did not reject; sensitive-value"),
  );

  assert.equal(
    diagnostic,
    "widget-e2e scenario=strict phase=assert_solve category=assertion_missing status=failed\n",
  );
  assert.equal(diagnostic.includes("sensitive-value"), false);
});

test("driver CLI categorizes a missing lock as assets", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "cap-widget-driver-missing-lock-"));
  t.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await runDriver(root), {
    code: 1,
    signal: null,
    stdout: "",
    stderr:
      "widget-e2e scenario=driver phase=assets category=assets status=failed\n",
  });
});

test("driver CLI sanitizes a browser launch failure", async (t) => {
  const sensitive = "/sensitive/local/browser-path";
  const root = await createDriverRoot(
    t,
    `export const chromium = { launch: async () => { throw new Error(${JSON.stringify(sensitive)}); } };`,
  );

  const result = await runDriver(root);

  assert.deepEqual(result, {
    code: 1,
    signal: null,
    stdout: "",
    stderr:
      "widget-e2e scenario=driver phase=browser_launch category=browser_launch status=failed\n",
  });
  assert.equal(result.stderr.includes(sensitive), false);
});

test("strict marker installer covers every random eight-check sample", () => {
  const attributes = new Set();
  const navigator = { mimeTypes: [] };
  const document = { documentElement: null, hasFocus: () => false };
  const window = { navigator, document };

  installStrictAutomationMarkers(window, navigator, document);

  const hitCategories = [
    navigator.webdriver === true,
    Object.getOwnPropertyNames(navigator).length !== 0,
    Object.hasOwn(window, "cdc_widget_e2e"),
    Object.hasOwn(window, "_selenium"),
    Object.hasOwn(document, "__webdriver_evaluate"),
    attributes.has("data-webdriver"),
    window.exposedFn.toString().includes("exposeBindingHandle"),
    navigator.userAgent.includes("HeadlessChrome"),
    document.hasFocus() && window.outerWidth === 0 && window.outerHeight === 0,
    Object.getPrototypeOf(navigator.mimeTypes) !== Array.prototype,
    navigator.productSub !== "20030107" &&
      navigator.userAgent
        .toLowerCase()
        .split(/[\s/(),;]/)
        .some((token) => ["chrome", "safari", "opera"].includes(token)),
    Object.hasOwn(window, "fixture_Array"),
  ].filter(Boolean).length;

  assert.equal(hitCategories, 11);
  assert.ok(18 - hitCategories < 8);
  assert.equal(window.process, undefined);
  assert.equal(window.external, undefined);
});

test("scenario deadline rejects safely and runs cleanup", async () => {
  let cleaned = false;

  await assert.rejects(
    withDeadline(
      "strict",
      5,
      () => new Promise(() => {}),
      async () => {
        cleaned = true;
      },
    ),
    { message: "strict scenario timed out" },
  );
  assert.equal(cleaned, true);
});

test("scenario deadline clears its timer after success", async () => {
  let cleaned = false;

  assert.equal(
    await withDeadline(
      "format1",
      5,
      async () => "done",
      async () => {
        cleaned = true;
      },
    ),
    "done",
  );
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(cleaned, false);
});

test("strict console policy accepts only the exact Chromium 403 error", () => {
  assert.equal(
    isExpectedStrictConsoleError("error", STRICT_403_CONSOLE_ERROR),
    true,
  );
  assert.equal(
    isExpectedStrictConsoleError("error", "[instr_blocked] automated browser"),
    false,
  );
  assert.equal(
    isExpectedStrictConsoleError("warning", STRICT_403_CONSOLE_ERROR),
    false,
  );
});
