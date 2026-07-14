import { createHash } from "node:crypto";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const PACKAGE = "capjs-core";
const VERSION = "0.1.1";
const SEMANTIC_REFERENCE_COMMIT = "f9ffadb";
const NPM_RESOLVED = "https://registry.npmjs.org/capjs-core/-/capjs-core-0.1.1.tgz";
const NPM_INTEGRITY =
  "sha512-I5ZAsG6avdMFs3RxEbNFj9VggWMV6JEUIUvKFCOLR2Q9plxrEe+i4515ejtkCP6nkyE8b75L81ygjYZKmugWMg==";
const SCHEMA = "https://github.com/luckygc/cap-server/fixtures/capjs-core-v1";
const SCHEMA_VERSION = 1;
const SECRET = "0123456789abcdef0123456789abcdef";
const NOW = 4_102_444_800_000;
const TTL_MS = 600_000;
const SOURCE_NAMES = ["crypto.js", "index.js", "instrumentation.js", "prng.js", "rsw.js"];
// These digests identify files unpacked from the official npm artifact above. They do not prove
// that the npm tarball corresponds to SEMANTIC_REFERENCE_COMMIT.
const NPM_ARTIFACT_SOURCE_SHA256 = {
  "crypto.js": "a7cdbe4fc286475d1279edfdf4ef5a2377949f795b2ec83a45514acc23539f17",
  "index.js": "05b3ea7b00d29af72e2ccb7f0770e4b78a99835c8cad4af175315e9d3319e1bc",
  "instrumentation.js": "73c7ab9f4b89dd30036ae361ec5ec3992dc8600508009e49f935a782408fb970",
  "prng.js": "62603a23e7d6c6538e65cea26b9e8abd5eaac967f73968de2734dd3c03cb0ed2",
  "rsw.js": "200f91cd42677377d214e48b0cd476dfe599fdae93f13e8dbea5631617ca1477",
};

const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== "--output") {
  throw new Error(
    "usage: node tools/fixtures/generate-capjs-core-fixtures.mjs --output <directory>",
  );
}

const outputDirectory = resolve(args[1]);
const packageRoot = resolve(process.cwd(), "node_modules", PACKAGE);
const packageJson = JSON.parse(await readFile(resolve(packageRoot, "package.json"), "utf8"));
if (packageJson.name !== PACKAGE || packageJson.version !== VERSION) {
  throw new Error(`expected ${PACKAGE}@${VERSION} in ${resolve(process.cwd(), "node_modules")}`);
}
const packageLock = JSON.parse(
  await readFile(resolve(process.cwd(), "package-lock.json"), "utf8"),
);
const lockedPackage = packageLock.packages?.[`node_modules/${PACKAGE}`];
if (
  lockedPackage?.version !== VERSION ||
  lockedPackage?.resolved !== NPM_RESOLVED ||
  lockedPackage?.integrity !== NPM_INTEGRITY
) {
  throw new Error(`package-lock.json does not pin the expected ${PACKAGE}@${VERSION} artifact`);
}
const filesSha256 = Object.fromEntries(
  await Promise.all(
    SOURCE_NAMES.map(async (name) => [
      name,
      createHash("sha256")
        .update(await readFile(resolve(packageRoot, "src", name)))
        .digest("hex"),
    ]),
  ),
);
for (const name of SOURCE_NAMES) {
  if (filesSha256[name] !== NPM_ARTIFACT_SOURCE_SHA256[name]) {
    throw new Error(
      `${PACKAGE}@${VERSION} src/${name} does not match official npm artifact ${NPM_INTEGRITY}`,
    );
  }
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const core = await import(pathToFileURL(resolve(packageRoot, "src/index.js")).href);
const crypto = await import(pathToFileURL(resolve(packageRoot, "src/crypto.js")).href);
const prng = await import(pathToFileURL(resolve(packageRoot, "src/prng.js")).href);

Date.now = () => NOW;

const source = {
  npm: {
    filesSha256,
    integrity: lockedPackage.integrity,
    resolved: lockedPackage.resolved,
    version: lockedPackage.version,
  },
  package: PACKAGE,
  repository: "https://github.com/tiagozip/cap",
  semanticReferenceCommit: SEMANTIC_REFERENCE_COMMIT,
};

function envelope(kind, value) {
  return {
    generator: "tools/fixtures/generate-capjs-core-fixtures.mjs",
    kind,
    schema: SCHEMA,
    schemaVersion: SCHEMA_VERSION,
    source,
    ...value,
  };
}

function sorted(value) {
  if (Array.isArray(value)) return value.map(sorted);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, sorted(value[key])]),
    );
  }
  return value;
}

async function writeFixture(name, fixture) {
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(
    resolve(outputDirectory, name),
    `${JSON.stringify(sorted(fixture), null, 2)}\n`,
  );
}

function tokenPayload(token) {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
}

function solvePow(salt, target) {
  let nonce = 0;
  while (!crypto.sha256Hex(`${salt}${nonce}`).startsWith(target)) nonce++;
  return nonce;
}

function format1Solutions(token, challenge) {
  const state = prng.fnv1a(token);
  return Array.from({ length: challenge.c }, (_, index) => {
    const saltState = prng.fnv1aResume(state, String(index + 1));
    const targetState = prng.fnv1aResume(saltState, "d");
    const salt = prng.prngFromHash(saltState, challenge.s);
    const target = prng.prngFromHash(targetState, challenge.d);
    return solvePow(salt, target);
  });
}

function format1Derivation(token, index, size, difficulty) {
  const state = prng.fnv1a(token);
  const saltState = prng.fnv1aResume(state, String(index + 1));
  const targetState = prng.fnv1aResume(saltState, "d");
  return {
    salt: prng.prngFromHash(saltState, size),
    target: prng.prngFromHash(targetState, difficulty),
  };
}

function solveNumber(index, token, size, difficulty, candidates) {
  const { salt, target } = format1Derivation(token, index, size, difficulty);
  for (const candidate of candidates()) {
    if (crypto.sha256Hex(`${salt}${candidate.value}`).startsWith(target)) {
      return { ...candidate, jsText: String(candidate.value), salt, target };
    }
  }
  throw new Error(`Format 1 Number solver exhausted at index ${index}`);
}

function instrumentationOutput(metadata) {
  return {
    i: metadata.id,
    state: Object.fromEntries(
      metadata.vars.map((variable, index) => [variable, metadata.expectedVals[index]]),
    ),
    ts: NOW + 1,
  };
}

const validationOptions = {
  scope: "login",
  signToken: ({ scope, expires, iat }) => `fixture:${scope}:${expires}:${iat}`,
};
const format1Options = {
  challengeCount: 2,
  challengeDifficulty: 2,
  challengeSize: 8,
  expiresMs: TTL_MS,
  extra: { tenant: "fixture" },
  scope: "login",
};

const format1Challenge = await core.generateChallenge(SECRET, format1Options);
const format1Request = {
  solutions: format1Solutions(format1Challenge.token, format1Challenge.challenge),
  token: format1Challenge.token,
};
const format1Redeemed = await core.validateChallenge(
  SECRET,
  format1Request,
  validationOptions,
);
if (!format1Redeemed.success) throw new Error(`Format 1 redeem failed: ${format1Redeemed.reason}`);
await writeFixture(
  "format1.json",
  envelope("format1", {
    challenge: format1Challenge,
    now: NOW,
    options: format1Options,
    redeemed: format1Redeemed,
    request: format1Request,
    secret: SECRET,
    tokenPayload: tokenPayload(format1Challenge.token),
  }),
);

const numberPayload = {
  n: "000102030405060708090a0b0c0d0e0f101112131415161718",
  c: 3,
  s: 8,
  d: 2,
  exp: NOW + TTL_MS,
  iat: NOW,
};
const numberToken = crypto.jwtSign(numberPayload, SECRET);
const fractional = solveNumber(0, numberToken, 8, 2, function* () {
  for (let integer = 0; integer < 1_000_000; integer++) {
    yield { sourceDecimal: `${integer}.5`, value: integer + 0.5 };
  }
});
const exponent = solveNumber(1, numberToken, 8, 2, function* () {
  for (let offset = 0; offset < 1_000_000; offset++) {
    const sourceDecimal = (10n ** 21n + BigInt(offset) * 131072n).toString();
    yield { sourceDecimal, value: Number(sourceDecimal) };
  }
});
const largeRounded = solveNumber(2, numberToken, 8, 2, function* () {
  for (let offset = 1n; offset < 1_000_000n; offset += 2n) {
    const sourceDecimal = (9007199254740992n + offset).toString();
    yield { sourceDecimal, value: Number(sourceDecimal) };
  }
});
const numberRequest = {
  token: numberToken,
  solutions: [fractional.value, exponent.value, largeRounded.value],
};
const numberRedeemed = await core.validateChallenge(SECRET, numberRequest, {
  signToken: () => "format1-number-oracle",
});
if (!numberRedeemed.success) {
  throw new Error(`Format 1 Number oracle failed: ${numberRedeemed.reason}`);
}
await writeFixture(
  "format1-number-solutions.json",
  envelope("format1-number-solutions", {
    now: NOW,
    payload: numberPayload,
    redeemed: numberRedeemed,
    request: numberRequest,
    secret: SECRET,
    token: numberToken,
    vectors: { exponent, fractional, largeRounded },
  }),
);

function infinityFormat1Oracle() {
  for (let nonce = 0; nonce < 1_000_000; nonce++) {
    const payload = {
      n: nonce.toString(16).padStart(50, "0"),
      c: 2,
      s: 4,
      d: 1,
      exp: NOW + TTL_MS,
      iat: NOW,
    };
    const token = crypto.jwtSign(payload, SECRET);
    const values = [Infinity, -Infinity];
    const vectors = values.map((value, index) => ({
      ...format1Derivation(token, index, payload.s, payload.d),
      jsText: String(value),
      sourceJson: index === 0 ? "1e400" : "-1e400",
    }));
    if (
      vectors.every((vector, index) =>
        crypto.sha256Hex(`${vector.salt}${values[index]}`).startsWith(vector.target),
      )
    ) {
      return { payload, token, values, vectors };
    }
  }
  throw new Error("Format 1 Infinity oracle search exhausted");
}

function infinitySalt(value) {
  for (let candidate = 0; candidate < 1_000_000; candidate++) {
    const salt = candidate.toString(16).padStart(8, "0");
    if (crypto.sha256Hex(`${salt}${value}`).startsWith("0")) return salt;
  }
  throw new Error(`Format 2 Infinity salt search exhausted for ${value}`);
}

const format1Infinity = infinityFormat1Oracle();
const format1InfinityRedeemed = await core.validateChallenge(
  SECRET,
  { token: format1Infinity.token, solutions: format1Infinity.values },
  { signToken: () => "format1-infinity-oracle" },
);
const format2InfinityExpected = [Infinity, -Infinity].map((value) => ({
  protocol: "sha256-pow",
  salt: infinitySalt(value),
  target: "0",
}));
const format2InfinityPayload = {
  f: 2,
  n: "000102030405060708090a0b0c0d0e0f",
  exp: NOW + TTL_MS,
  iat: NOW,
  ev: crypto.encryptGcm({ expected: format2InfinityExpected }, SECRET, "cap:fmt2-v1"),
};
const format2InfinityToken = crypto.jwtSign(format2InfinityPayload, SECRET);
const format2InfinitySolutions = [{ nonce: Infinity }, { nonce: -Infinity }];
const format2InfinityRedeemed = await core.validateChallenge(
  SECRET,
  { token: format2InfinityToken, solutions: format2InfinitySolutions },
  { signToken: () => "format2-infinity-oracle" },
);
if (!format1InfinityRedeemed.success || !format2InfinityRedeemed.success) {
  throw new Error("Infinity overflow oracle failed");
}
await writeFixture(
  "number-overflow-solutions.json",
  envelope("number-overflow-solutions", {
    format1: {
      payload: format1Infinity.payload,
      redeemed: format1InfinityRedeemed,
      solutionSourceJson: ["1e400", "-1e400"],
      token: format1Infinity.token,
      vectors: format1Infinity.vectors,
    },
    format2: {
      expected: format2InfinityExpected,
      payload: format2InfinityPayload,
      redeemed: format2InfinityRedeemed,
      solutionSourceJson: ["1e400", "-1e400"],
      token: format2InfinityToken,
    },
    now: NOW,
    secret: SECRET,
  }),
);

const format1InstrumentationOptions = {
  ...format1Options,
  instrumentation: { blockAutomatedBrowsers: true, obfuscationLevel: 1 },
};
const format1InstrumentationChallenge = await core.generateChallenge(
  SECRET,
  format1InstrumentationOptions,
);
const format1InstrumentationPayload = tokenPayload(format1InstrumentationChallenge.token);
const format1InstrumentationMetadata = crypto.decryptGcm(
  format1InstrumentationPayload.ei,
  SECRET,
);
if (!format1InstrumentationMetadata) throw new Error("Format 1 metadata decrypt failed");
const format1InstrumentationRequest = {
  instr: instrumentationOutput(format1InstrumentationMetadata),
  solutions: format1Solutions(
    format1InstrumentationChallenge.token,
    format1InstrumentationChallenge.challenge,
  ),
  token: format1InstrumentationChallenge.token,
};
const format1InstrumentationRedeemed = await core.validateChallenge(
  SECRET,
  format1InstrumentationRequest,
  validationOptions,
);
const format1Blocked = await core.validateChallenge(
  SECRET,
  {
    solutions: format1InstrumentationRequest.solutions,
    token: format1InstrumentationRequest.token,
    instr_blocked: true,
  },
  validationOptions,
);
if (!format1InstrumentationRedeemed.success || format1Blocked.reason !== "instr_automated_browser") {
  throw new Error("Format 1 instrumentation oracle failed");
}
await writeFixture(
  "format1-instrumentation.json",
  envelope("format1-instrumentation", {
    blockedOracle: format1Blocked,
    challenge: format1InstrumentationChallenge,
    instrumentationMetadata: format1InstrumentationMetadata,
    now: NOW,
    options: format1InstrumentationOptions,
    redeemed: format1InstrumentationRedeemed,
    request: format1InstrumentationRequest,
    secret: SECRET,
    tokenPayload: format1InstrumentationPayload,
  }),
);

const rswFixture = JSON.parse(
  await readFile(
    resolve(repositoryRoot, "src/test/resources/fixtures/capjs-core-0.1.1/rsw.json"),
    "utf8",
  ),
);
const keypair = {
  N: rswFixture.N,
  bits: rswFixture.bits,
  p: rswFixture.p,
  q: rswFixture.q,
};
const format2Options = {
  challengeCount: 1,
  challengeDifficulty: 1,
  challengeSize: 4,
  expiresMs: TTL_MS,
  extra: { tenant: "fixture" },
  format: 2,
  instrumentation: { blockAutomatedBrowsers: true, obfuscationLevel: 1 },
  keypair,
  protocols: ["sha256-pow", "rsw", "instrumentation"],
  scope: "login",
  t: 8,
};
const format2Challenge = await core.generateChallenge(SECRET, format2Options);
const format2Payload = tokenPayload(format2Challenge.token);
const format2Metadata = crypto.decryptGcm(format2Payload.ev, SECRET, "cap:fmt2-v1");
if (!format2Metadata || format2Metadata.expected.length !== 3) {
  throw new Error("Format 2 metadata decrypt failed");
}
const format2Solutions = format2Metadata.expected.map((expected) => {
  if (expected.protocol === "sha256-pow") {
    return { nonce: solvePow(expected.salt, expected.target) };
  }
  if (expected.protocol === "rsw") return { y: expected.y };
  if (expected.protocol === "instrumentation") {
    return { instr: instrumentationOutput(expected.instrMeta) };
  }
  throw new Error(`unknown generated protocol: ${expected.protocol}`);
});
const format2Request = { solutions: format2Solutions, token: format2Challenge.token };
const format2Redeemed = await core.validateChallenge(SECRET, format2Request, validationOptions);
const blockedSolutions = format2Solutions.map((solution, index) =>
  index === 2 ? { blocked: true } : solution,
);
const format2Blocked = await core.validateChallenge(
  SECRET,
  { solutions: blockedSolutions, token: format2Challenge.token },
  validationOptions,
);
if (!format2Redeemed.success || format2Blocked.reason !== "instr_automated_browser") {
  throw new Error("Format 2 oracle failed");
}
await writeFixture(
  "format2.json",
  envelope("format2", {
    blockedOracle: format2Blocked,
    blockedSolutions,
    challenge: format2Challenge,
    expectedMetadata: format2Metadata,
    keypair,
    now: NOW,
    options: format2Options,
    redeemed: format2Redeemed,
    request: format2Request,
    secret: SECRET,
    tokenPayload: format2Payload,
  }),
);

const curatedNumbers = [
  ["negative-zero", -0],
  ["plain-upper-bound", 1e20],
  ["scientific-upper-bound", 1e21],
  ["plain-lower-bound", 1e-6],
  ["scientific-lower-bound", 1e-7],
  ["minimum-subnormal", Number.MIN_VALUE],
  ["maximum-finite", Number.MAX_VALUE],
  ["shortest-large-integer", 1000000000000000128],
];
let randomState = 0x243f6a8885a308d3n;
function nextBits() {
  randomState = BigInt.asUintN(64, randomState ^ (randomState << 13n));
  randomState = BigInt.asUintN(64, randomState ^ (randomState >> 7n));
  randomState = BigInt.asUintN(64, randomState ^ (randomState << 17n));
  return randomState;
}
function bitsOf(value) {
  const buffer = Buffer.allocUnsafe(8);
  buffer.writeDoubleBE(value);
  return buffer.readBigUInt64BE();
}
const numberVectors = curatedNumbers.map(([label, value]) => ({
  bits: bitsOf(value).toString(16).padStart(16, "0"),
  jsString: String(value),
  label,
}));
for (let index = 0; numberVectors.length < 500; index++) {
  const bits = nextBits();
  const buffer = Buffer.allocUnsafe(8);
  buffer.writeBigUInt64BE(bits);
  const value = buffer.readDoubleBE();
  if (!Number.isFinite(value)) continue;
  numberVectors.push({
    bits: bits.toString(16).padStart(16, "0"),
    jsString: String(value),
    label: `random-${index}`,
  });
}
await writeFixture(
  "number-string-vectors.json",
  envelope("number-string-vectors", {
    algorithm: "xorshift64 with uint64 truncation after each step; Node String(number)",
    now: NOW,
    vectors: numberVectors,
  }),
);

process.stdout.write(
  `${JSON.stringify({ output: outputDirectory, package: `${PACKAGE}@${VERSION}`, fixtures: 6 })}\n`,
);
