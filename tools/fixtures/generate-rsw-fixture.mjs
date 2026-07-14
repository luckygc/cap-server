import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const GENERATOR =
  "tools/fixtures/generate-rsw-fixture.mjs using core/src/rsw.js buildRswMinter().mint()";
const [mode, upstreamRoot] = process.argv.slice(2);
if (mode !== "--check" || !upstreamRoot) {
  throw new Error(
    "usage: node tools/fixtures/generate-rsw-fixture.mjs --check <cap-upstream-root>",
  );
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const fixturePath = resolve(
  repositoryRoot,
  "src/test/resources/fixtures/capjs-core-0.1.1/rsw.json",
);
const upstreamRswPath = resolve(upstreamRoot, "core/src/rsw.js");
const fixture = JSON.parse(await readFile(fixturePath, "utf8"));
const originalSource = await readFile(upstreamRswPath, "utf8");
const cryptoImport = 'import { randomBytes } from "node:crypto";';
if (!originalSource.includes(cryptoImport)) {
  throw new Error("upstream rsw.js crypto import changed");
}

const temporaryDirectory = await mkdtemp(join(tmpdir(), "cap-rsw-fixture-"));
try {
  const copiedRswPath = join(temporaryDirectory, "rsw.mjs");
  const shimPath = join(temporaryDirectory, "deterministic-crypto.mjs");
  await writeFile(
    copiedRswPath,
    originalSource.replace(
      cryptoImport,
      'import { randomBytes } from "./deterministic-crypto.mjs";',
    ),
  );
  await writeFile(
    shimPath,
    `export const requestedSizes = [];
export function randomBytes(size) {
  requestedSizes.push(size);
  if (requestedSizes.length > 2) throw new Error("unexpected randomBytes call");
  return Buffer.from(Array.from({ length: size }, (_, index) => index & 0xff));
}
`,
  );

  const { buildRswMinter } = await import(pathToFileURL(copiedRswPath).href);
  const minter = buildRswMinter(
    {
      N: BigInt(fixture.N),
      p: BigInt(fixture.p),
      q: BigInt(fixture.q),
      t: fixture.t,
    },
    { bits: fixture.bits },
  );
  const minted = minter.mint();
  const { requestedSizes } = await import(pathToFileURL(shimPath).href);
  const expectedRandomSizes = [fixture.bits / 8, 32];
  if (JSON.stringify(requestedSizes) !== JSON.stringify(expectedRandomSizes)) {
    throw new Error(`unexpected randomBytes sizes: ${requestedSizes.join(",")}`);
  }
  const randomR = Buffer.from(
    Array.from({ length: 32 }, (_, index) => index),
  );
  const rHex = randomR.toString("hex");
  const rDecimal = BigInt(`0x${rHex}`).toString();
  const actual = {
    generator: GENERATOR,
    g: minter.g.toString(),
    h: minter.h.toString(),
    NHex: minter.N_hex,
    r: rDecimal,
    rHex,
    xHex: minted.x_hex,
    yHex: minted.y_hex,
  };
  const expected = {
    generator: fixture.source.generator,
    g: fixture.g,
    h: fixture.h,
    NHex: fixture.NHex,
    r: fixture.r,
    rHex: fixture.rHex,
    xHex: fixture.xHex,
    yHex: fixture.yHex,
  };
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `checked-in RSW fixture differs from upstream mint:\n${JSON.stringify(actual, null, 2)}`,
    );
  }
  console.log(
    JSON.stringify({
      upstream: "capjs-core 0.1.1 f9ffadb core/src/rsw.js",
      deterministicRandomCalls: requestedSizes,
      checked: ["g", "h", "NHex", "r", "rHex", "xHex", "yHex"],
    }),
  );
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}
