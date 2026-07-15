import assert from "node:assert/strict";
import test from "node:test";

import {
  STRICT_403_CONSOLE_ERROR,
  isExpectedStrictConsoleError,
  withDeadline,
} from "./run-widget-e2e.mjs";

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
