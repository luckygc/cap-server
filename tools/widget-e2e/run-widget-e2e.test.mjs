import assert from "node:assert/strict";
import test from "node:test";

import {
  STRICT_403_CONSOLE_ERROR,
  formatFailureDiagnostic,
  installStrictAutomationMarkers,
  isExpectedStrictConsoleError,
  withDeadline,
} from "./run-widget-e2e.mjs";

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
