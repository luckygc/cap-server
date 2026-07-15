import { readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";

export const EXPECTED_PACKAGES = {
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

const ARTIFACTS = {
  widgetScript: "node_modules/@cap.js/widget/cap.min.js",
  wasm: "node_modules/@cap.js/wasm/browser/cap_wasm_bg.wasm",
  playwrightModule: "node_modules/playwright/index.mjs",
};

async function readPackageLock(root) {
  let contents;
  try {
    contents = await readFile(resolve(root, "package-lock.json"), "utf8");
  } catch {
    throw new Error("package-lock.json is missing or unreadable");
  }

  try {
    return JSON.parse(contents);
  } catch {
    throw new Error("package-lock.json is not valid JSON");
  }
}

function verifyPackages(lock) {
  if (
    lock === null ||
    typeof lock !== "object" ||
    lock.packages === null ||
    typeof lock.packages !== "object"
  ) {
    throw new Error("package-lock.json packages object is missing");
  }

  for (const [packagePath, expected] of Object.entries(EXPECTED_PACKAGES)) {
    const entry = lock.packages[packagePath];
    if (entry === null || typeof entry !== "object") {
      throw new Error(`package-lock entry ${packagePath} is missing`);
    }
    for (const field of ["version", "resolved", "integrity"]) {
      if (entry[field] !== expected[field]) {
        throw new Error(`package-lock entry ${packagePath} has unexpected ${field}`);
      }
    }
  }
}

async function verifyArtifact(root, packagePath, artifactPath) {
  try {
    const metadata = await stat(resolve(root, artifactPath));
    if (!metadata.isFile()) {
      throw new Error("not a file");
    }
  } catch {
    throw new Error(`artifact ${packagePath}/${artifactPath.split("/").at(-1)} is missing`);
  }
}

export async function resolveWidgetAssets(root) {
  const absoluteRoot = resolve(root);
  const lock = await readPackageLock(absoluteRoot);
  verifyPackages(lock);

  await verifyArtifact(
    absoluteRoot,
    "@cap.js/widget",
    ARTIFACTS.widgetScript,
  );
  await verifyArtifact(absoluteRoot, "@cap.js/wasm", ARTIFACTS.wasm);
  await verifyArtifact(
    absoluteRoot,
    "playwright",
    ARTIFACTS.playwrightModule,
  );

  return Object.fromEntries(
    Object.entries(ARTIFACTS).map(([name, path]) => [
      name,
      resolve(absoluteRoot, path),
    ]),
  );
}
