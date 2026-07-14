import crypto from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const [mode, upstreamRoot] = process.argv.slice(2);
if (!new Set(["--print", "--check"]).has(mode) || !upstreamRoot) {
  throw new Error(
    "usage: node tools/fixtures/generate-format2-fixture.mjs <--print|--check> <cap-upstream-root>",
  );
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const fixturePath = resolve(
  repositoryRoot,
  "src/test/resources/fixtures/capjs-core-0.1.1/format2.json",
);
const upstreamSourceDirectory = resolve(upstreamRoot, "core/src");
const sourceNames = ["index.js", "crypto.js", "prng.js", "rsw.js", "instrumentation.js"];
const temporaryDirectory = await mkdtemp(join(tmpdir(), "cap-format2-fixture-"));

try {
  for (const sourceName of sourceNames) {
    let source = await readFile(resolve(upstreamSourceDirectory, sourceName), "utf8");
    source = source
      .replace(
        'import crypto from "node:crypto";',
        'import crypto from "./deterministic-crypto.mjs";',
      )
      .replace(
        'import { randomBytes } from "node:crypto";',
        'import { randomBytes } from "./deterministic-crypto.mjs";',
      )
      .replace(
        'import { randomBytes, randomInt } from "node:crypto";',
        'import { randomBytes, randomInt } from "./deterministic-crypto.mjs";',
      );
    await writeFile(resolve(temporaryDirectory, sourceName), source);
  }
  await writeFile(
    resolve(temporaryDirectory, "deterministic-crypto.mjs"),
    `import crypto from "node:crypto";
let byte = 0;
let integer = 0;
export const requestedSizes = [];
export function randomBytes(size) {
  requestedSizes.push(size);
  const value = Buffer.from(Array.from({length: size}, () => (byte++ * 29 + 7) & 255));
  return value;
}
export function randomInt(min, max) {
  return min + ((integer++ * 7919 + 104729) % (max - min));
}
export default {
  createHash: crypto.createHash,
  createHmac: crypto.createHmac,
  timingSafeEqual: crypto.timingSafeEqual,
  createCipheriv: crypto.createCipheriv,
  createDecipheriv: crypto.createDecipheriv,
  randomBytes,
};
`,
  );

  let mathState = 0x12345678;
  Math.random = () => {
    mathState = (Math.imul(mathState, 1664525) + 1013904223) >>> 0;
    return mathState / 0x100000000;
  };
  Date.now = () => 1_700_000_000_000;

  const rswFixture = JSON.parse(
    await readFile(
      resolve(repositoryRoot, "src/test/resources/fixtures/capjs-core-0.1.1/rsw.json"),
      "utf8",
    ),
  );
  const core = await import(pathToFileURL(resolve(temporaryDirectory, "index.js")).href);
  const cryptoModule = await import(
    pathToFileURL(resolve(temporaryDirectory, "crypto.js")).href
  );
  const deterministicCrypto = await import(
    pathToFileURL(resolve(temporaryDirectory, "deterministic-crypto.mjs")).href
  );
  const secret = "0123456789abcdef0123456789abcdef";
  const keypair = {
    N: rswFixture.N,
    p: rswFixture.p,
    q: rswFixture.q,
    bits: rswFixture.bits,
  };
  const generated = await core.generateChallenge(secret, {
    format: 2,
    protocols: ["sha256-pow", "rsw", "instrumentation"],
    challengeCount: 1,
    challengeSize: 4,
    challengeDifficulty: 1,
    expiresMs: 600_000,
    scope: "login",
    extra: { tenant: "alpha" },
    keypair,
    t: 8,
    instrumentation: { blockAutomatedBrowsers: true, obfuscationLevel: 1 },
  });
  const tokenPayload = JSON.parse(
    Buffer.from(generated.token.split(".")[1], "base64url").toString("utf8"),
  );
  const expectedMetadata = cryptoModule.decryptGcm(
    tokenPayload.ev,
    secret,
    "cap:fmt2-v1",
  );
  if (!expectedMetadata || expectedMetadata.expected.length !== 3) {
    throw new Error("upstream Format 2 expected metadata was not generated");
  }

  const [shaExpected, rswExpected, instrumentationExpected] = expectedMetadata.expected;
  let nonce = 0;
  while (!cryptoModule.sha256Hex(`${shaExpected.salt}${nonce}`).startsWith(shaExpected.target)) {
    nonce++;
  }
  const instrumentationMeta = instrumentationExpected.instrMeta;
  const instrumentationState = Object.fromEntries(
    instrumentationMeta.vars.map((variable, index) => [
      variable,
      instrumentationMeta.expectedVals[index],
    ]),
  );
  const solutions = [
    { nonce },
    { y: rswExpected.y },
    {
      instr: {
        i: instrumentationMeta.id,
        state: instrumentationState,
        ts: 1_700_000_000_001,
      },
    },
  ];
  const redeemed = await core.validateChallenge(
    secret,
    { token: generated.token, solutions },
    {
      scope: "login",
      signToken: ({ scope, expires, iat }) => `signed:${scope}:${expires}:${iat}`,
    },
  );
  if (!redeemed.success) {
    throw new Error(`upstream Format 2 redeem failed: ${JSON.stringify(redeemed)}`);
  }
  const format1Metadata = { id: "format1-vector", expectedVals: [1, 2, 3, 4] };
  const format2Metadata = { expected: [{ protocol: "rsw", y: "00ab" }] };
  const format1Encrypted = cryptoModule.encryptGcm(format1Metadata, secret);
  const format2Encrypted = cryptoModule.encryptGcm(
    format2Metadata,
    secret,
    "cap:fmt2-v1",
  );
  const minimalObjectEncrypted = cryptoModule.encryptGcm({}, secret);
  const numberNonceValues = [
    ["one", 1.0],
    ["negative-zero", -0],
    ["fraction", 1.5],
    ["plain-upper-bound", 1e20],
    ["scientific-upper-bound", 1e21],
    ["plain-lower-bound", 1e-6],
    ["scientific-lower-bound", 1e-7],
    ["minimum-subnormal", Number.MIN_VALUE],
    ["shortest-large-integer", 1000000000000000128],
    ["rounded-long", Number(9007199254740993n)],
  ];
  const numberNonceVectors = numberNonceValues.map(([label, value], index) => {
    const jsString = String(value);
    const salt = `nonce-${label}:`;
    const hash = cryptoModule.sha256Bytes(`${salt}${jsString}`);
    const target = hash.toString("hex").slice(0, 3);
    const wireTarget = index % 2 === 0 ? target.toUpperCase() : target;
    if (!cryptoModule.powMatchesPrefix(hash, cryptoModule.parseHexPrefix(wireTarget))) {
      throw new Error(`upstream rejected number nonce vector ${label}`);
    }
    return { label, value, jsString, salt, target: wireTarget };
  });

  const actual = {
    source: {
      package: "capjs-core",
      version: "0.1.1",
      commit: "f9ffadb",
      generator:
        "tools/fixtures/generate-format2-fixture.mjs calling generateChallenge() and validateChallenge()",
    },
    secret,
    now: 1_700_000_000_000,
    options: {
      protocols: ["sha256-pow", "rsw", "instrumentation"],
      challengeCount: 1,
      challengeSize: 4,
      challengeDifficulty: 1,
      expiresMs: 600_000,
      scope: "login",
      extra: { tenant: "alpha" },
      t: 8,
      blockAutomatedBrowsers: true,
      obfuscationLevel: 1,
    },
    keypair,
    generated,
    tokenPayload,
    expectedMetadata,
    solutions,
    redeemed,
    signatureHex: cryptoModule.jwtSigHex(generated.token),
    cryptoVectors: {
      format1: { metadata: format1Metadata, encrypted: format1Encrypted },
      format2: { metadata: format2Metadata, encrypted: format2Encrypted },
      minimalFormat1: { metadata: {}, encrypted: minimalObjectEncrypted },
    },
    numberNonceVectors,
    deterministicRandomCalls: deterministicCrypto.requestedSizes,
  };

  const javaFixture = JSON.parse(
    await readFile(
      resolve(
        repositoryRoot,
        "src/test/resources/fixtures/capjs-core-0.1.1/format2-java.json",
      ),
      "utf8",
    ),
  );
  const javaRedeemed = await core.validateChallenge(
    secret,
    {
      token: javaFixture.generated.token,
      solutions: javaFixture.solutions,
    },
    {
      scope: "login",
      signToken: ({ scope, expires, iat }) => `java:${scope}:${expires}:${iat}`,
    },
  );
  if (!javaRedeemed.success) {
    throw new Error(
      `upstream rejected deterministic Java Format 2 fixture: ${JSON.stringify(javaRedeemed)}`,
    );
  }

  if (mode === "--print") {
    process.stdout.write(`${JSON.stringify(actual, null, 2)}\n`);
  } else {
    const expected = JSON.parse(await readFile(fixturePath, "utf8"));
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error("checked-in Format 2 fixture differs from unmodified upstream behavior");
    }
    console.log(
      JSON.stringify({
        upstream: "capjs-core 0.1.1 f9ffadb core/src/index.js",
        generated: true,
        redeemed: true,
        protocols: actual.options.protocols,
        javaGeneratedRedeemed: true,
      }),
    );
  }
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}
