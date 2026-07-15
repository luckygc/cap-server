import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { inflateRawSync } from "node:zlib";

const [mode, upstreamRoot] = process.argv.slice(2);
if (!new Set(["--print", "--check"]).has(mode) || !upstreamRoot) {
  throw new Error(
    "usage: node tools/fixtures/generate-instrumentation-fixture.mjs <--print|--check> <cap-upstream-root>",
  );
}

const upstreamPath = resolve(upstreamRoot, "core/src/instrumentation.js");
const originalSource = await readFile(upstreamPath, "utf8");
const cryptoImport = 'import { randomBytes, randomInt } from "node:crypto";';
if (!originalSource.includes(cryptoImport)) {
  throw new Error("upstream instrumentation.js crypto import changed");
}

const temporaryDirectory = await mkdtemp(join(tmpdir(), "cap-instrumentation-fixture-"));
try {
  const copiedPath = join(temporaryDirectory, "instrumentation.mjs");
  const shimPath = join(temporaryDirectory, "deterministic-crypto.mjs");
  await writeFile(
    copiedPath,
    originalSource.replace(
      cryptoImport,
      'import { randomBytes, randomInt } from "./deterministic-crypto.mjs";',
    ),
  );
  await writeFile(
    shimPath,
    `let byte = 0;
let integer = 0;
export function randomBytes(size) {
  return Buffer.from(Array.from({length:size}, () => (byte++ * 29 + 7) & 255));
}
export function randomInt(min, max) {
  const range = max - min;
  const value = min + ((integer++ * 7919 + 104729) % range);
  return value;
}
`,
  );

  let state = 0x12345678;
  Math.random = () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 0x100000000;
  };
  Date.now = () => 1_700_000_000_000;

  const { generateInstrumentation } = await import(pathToFileURL(copiedPath).href);
  const generated = await generateInstrumentation({
    blockAutomatedBrowsers: false,
    obfuscationLevel: 1,
    ttlMs: 300_000,
  });
  const script = inflateRawSync(Buffer.from(generated.instrumentation, "base64")).toString(
    "utf8",
  );
  const actual = {
    source: {
      version: "capjs-core 0.1.1",
      commit: "f9ffadb",
      file: "core/src/instrumentation.js",
      generator:
        "tools/fixtures/generate-instrumentation-fixture.mjs calling generateInstrumentation()",
    },
    options: {
      blockAutomatedBrowsers: false,
      obfuscationLevel: 1,
      ttlMs: 300000,
      now: 1700000000000,
    },
    ...generated,
    script,
  };
  if (mode === "--print") {
    process.stdout.write(`${JSON.stringify(actual, null, 2)}\n`);
  } else {
    const fixturePath = resolve(
      "cap-server/src/test/resources/fixtures/capjs-core-0.1.1/instrumentation.json",
    );
    const expected = JSON.parse(await readFile(fixturePath, "utf8"));
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error("checked-in instrumentation fixture differs from upstream");
    }
    console.log(
      JSON.stringify({
        upstream: "capjs-core 0.1.1 f9ffadb core/src/instrumentation.js",
        checked: [
          "id",
          "expires",
          "expectedVals",
          "vars",
          "blockAutomatedBrowsers",
          "instrumentation",
          "script",
        ],
      }),
    );
  }
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}
