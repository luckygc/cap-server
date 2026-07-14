import { appendFile, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const packageRoot = resolve(process.cwd(), "node_modules/capjs-core");
const cryptoPath = resolve(packageRoot, "src/crypto.js");
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const generator = resolve(scriptDirectory, "generate-capjs-core-fixtures.mjs");
const output = await mkdtemp(resolve(tmpdir(), "cap-fixture-tamper-test-"));
const originalCrypto = await readFile(cryptoPath);

try {
  await appendFile(cryptoPath, "\n// tampered by fixture generator regression test\n");
  const result = spawnSync(process.execPath, [generator, "--output", output], {
    cwd: process.cwd(),
    encoding: "utf8",
  });
  if (result.status === 0) {
    throw new Error("generator accepted a tampered capjs-core npm artifact");
  }
  if (!result.stderr.includes("does not match official npm artifact")) {
    throw new Error(`unexpected generator failure: ${result.stderr}`);
  }
  process.stdout.write(
    `${JSON.stringify({ tampered: "src/crypto.js", rejected: true })}\n`,
  );
} finally {
  await writeFile(cryptoPath, originalCrypto);
  await rm(output, { recursive: true, force: true });
}
