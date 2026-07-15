import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";

import { resolveWidgetAssets } from "./widget-assets.mjs";

const PACKAGES = {
  "node_modules/@cap.js/widget": {
    version: "0.1.56",
    resolved: "https://registry.npmjs.org/@cap.js/widget/-/widget-0.1.56.tgz",
    integrity:
      "sha512-j640dNNNIF8IWmwqmSx0ihgU8sz/6Jm9mHveeDWUk8aXVqFm+2TSsp5bawtMtgf0aa7rFkmT9p76jrqO1uSEpQ==",
  },
  "node_modules/@cap.js/wasm": {
    version: "0.0.7",
    resolved: "https://registry.npmjs.org/@cap.js/wasm/-/wasm-0.0.7.tgz",
    integrity:
      "sha512-IgUjrPOUBaOjTp+BkrhfEBBeQ4An7fQiSWWezDy9Uvd+OdTYm4+h3AJU0j/CpHYayp7FltZU+UePC6p28oGQaw==",
  },
  "node_modules/playwright": {
    version: "1.52.0",
    resolved: "https://registry.npmjs.org/playwright/-/playwright-1.52.0.tgz",
    integrity:
      "sha512-JAwMNMBlxJ2oD1kce4KPtMkDeKGHQstdpFPcPH3maElAXon/QZeTvtsfXmTMRyO9TslfoYOXkSsvao2nE1ilTw==",
  },
};

const ARTIFACTS = [
  "node_modules/@cap.js/widget/cap.min.js",
  "node_modules/@cap.js/wasm/browser/cap_wasm_bg.wasm",
  "node_modules/playwright/index.mjs",
];

async function createRoot(t) {
  const root = await mkdtemp(join(tmpdir(), "cap-widget-assets-"));
  t.after(() => rm(root, { recursive: true, force: true }));

  await writeFile(
    join(root, "package-lock.json"),
    JSON.stringify({ packages: PACKAGES }),
  );
  for (const artifact of ARTIFACTS) {
    const path = join(root, artifact);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, "fixture");
  }
  return root;
}

test("resolves artifacts from an exact package lock", async (t) => {
  const root = await createRoot(t);

  assert.deepEqual(await resolveWidgetAssets(root), {
    widgetScript: resolve(root, ARTIFACTS[0]),
    wasm: resolve(root, ARTIFACTS[1]),
    playwrightModule: resolve(root, ARTIFACTS[2]),
  });
});

test("rejects a tampered package integrity", async (t) => {
  const root = await createRoot(t);
  const packages = structuredClone(PACKAGES);
  packages["node_modules/@cap.js/widget"].integrity = "sha512-tampered";
  await writeFile(join(root, "package-lock.json"), JSON.stringify({ packages }));

  await assert.rejects(resolveWidgetAssets(root), (error) => {
    assert.match(error.message, /@cap\.js\/widget.*integrity/);
    assert.doesNotMatch(error.message, new RegExp(root));
    return true;
  });
});

test("rejects a missing artifact", async (t) => {
  const root = await createRoot(t);
  await rm(join(root, ARTIFACTS[1]));

  await assert.rejects(resolveWidgetAssets(root), (error) => {
    assert.match(error.message, /@cap\.js\/wasm.*cap_wasm_bg\.wasm/);
    assert.doesNotMatch(error.message, new RegExp(root));
    return true;
  });
});
