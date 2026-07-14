import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { inflateRawSync } from "node:zlib";

const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== "--fixture") {
  throw new Error(
    "usage: node tools/fixtures/check-instrumentation-browser.mjs --fixture <format1-instrumentation.json>",
  );
}

const fixture = JSON.parse(await readFile(resolve(args[1]), "utf8"));
if (fixture.kind !== "format1-instrumentation") {
  throw new Error("expected a generated format1-instrumentation fixture");
}
if (
  fixture.options?.instrumentation?.blockAutomatedBrowsers !== true ||
  fixture.instrumentationMetadata?.blockAutomatedBrowsers !== true
) {
  throw new Error("fixture must authenticate blockAutomatedBrowsers=true");
}
const playwrightPath = resolve(process.cwd(), "node_modules/playwright/index.mjs");
const { chromium } = await import(pathToFileURL(playwrightPath).href);
const script = inflateRawSync(
  Buffer.from(fixture.challenge.instrumentation, "base64"),
).toString("utf8");

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.setContent(
    "<script>window.addEventListener('message',event=>{if(event.data?.type==='cap:instr')window.capInstrumentationMessage=event.data})</script><iframe></iframe>",
  );
  const frame = page.frames()[1];
  await frame.setContent(`<script>${script.replaceAll("</script", "<\\/script")}</script>`);
  await page.waitForFunction(() => window.capInstrumentationMessage, null, { timeout: 10_000 });
  const message = await page.evaluate(() => window.capInstrumentationMessage);
  const expectedId = fixture.instrumentationMetadata.id;
  const fields = Object.keys(message).sort();
  if (
    JSON.stringify(fields) !== JSON.stringify(["blocked", "nonce", "result", "type"]) ||
    message.type !== "cap:instr" ||
    message.nonce !== expectedId ||
    message.result !== "" ||
    message.blocked !== true
  ) {
    throw new Error(
      `headless Chromium did not emit the exact blocked wire: ${JSON.stringify(message)}`,
    );
  }
  process.stdout.write(
    `${JSON.stringify({ browser: "chromium", executed: true, blocked: true })}\n`,
  );
} finally {
  await browser.close();
}
