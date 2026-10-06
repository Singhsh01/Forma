#!/usr/bin/env node
// Captures the documentation screenshots (docs/screenshots) from the running app and engine.
// Usage: start the engine and the web dev server, then: node scripts/capture-screenshots.mjs [baseUrl]
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(root, "web", "package.json"));
const { chromium, devices } = require("@playwright/test");
const base = process.argv[2] ?? "http://localhost:5173";
const out = join(root, "docs", "screenshots");
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
const shot = async (page, name) => {
  await page.screenshot({ path: join(out, name), animations: "disabled", timeout: 90_000 });
  console.log("ok  ", name);
};
// a fresh tab per page: a busy software-WebGL canvas can stall navigation away from it
const fresh = async (ctx, old, path) => {
  if (old) await old.close();
  const p = await ctx.newPage();
  await p.goto(base + path, { waitUntil: "domcontentloaded", timeout: 120_000 });
  return p;
};
const settle = (page, ms = 4000) => page.waitForTimeout(ms);
const ready = async (page) => {
  await page.waitForFunction(() => document.querySelector('[data-testid="stage"]')?.getAttribute("data-preset"));
  await settle(page);
};

// desktop
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  let page = null;
  page = await fresh(ctx, page, "/");
  await settle(page, 6000);
  await shot(page, "landing-desktop.png");
  await page.locator("#worlds").scrollIntoViewIfNeeded();
  await settle(page, 2500);
  await shot(page, "landing-gallery-desktop.png");

  page = await fresh(ctx, page, "/studio");
  await ready(page);
  await shot(page, "studio-library-desktop.png");

  await page.getByTestId("replay-toggle").click();
  await page.waitForSelector(".timeline-status");
  await page.getByTestId("replay-scrub").fill("40");
  await settle(page, 3000);
  await shot(page, "studio-replay-desktop.png");
  await page.getByTestId("replay-toggle").click();

  await page.getByTestId("tab-rules").click();
  await settle(page, 2500);
  await shot(page, "studio-rules-desktop.png");

  await page.getByTestId("tab-checks").click();
  await settle(page, 2000);
  await shot(page, "studio-checks-desktop.png");

  // a live generation and refinement against the engine
  await page.getByTestId("preset-organic").click();
  await ready(page);
  await page.getByTestId("generate").click();
  await page.waitForFunction(() => document.querySelector('[data-testid="stage"]')?.getAttribute("data-source") === "live", null, { timeout: 60_000 });
  await page.getByTestId("tab-refine").click();
  await page.getByTestId("run-refine").click();
  await page.waitForFunction(() => document.querySelector('[data-testid="stage"]')?.getAttribute("data-refined") === "1", null, { timeout: 90_000 });
  await settle(page, 3000);
  await page.locator(".refine-results").scrollIntoViewIfNeeded();
  await shot(page, "studio-refine-desktop.png");

  await page.getByTestId("preset-escher").click();
  await ready(page);
  await page.getByTestId("tab-checks").click();
  await settle(page, 2000);
  await shot(page, "studio-escher-isometric-desktop.png");

  await page.getByTestId("preset-cathedral").click();
  await ready(page);
  await page.getByTestId("mode-blueprint").click();
  await settle(page);
  await shot(page, "studio-cathedral-blueprint-desktop.png");
  await page.getByTestId("mode-ink").click();
  await page.getByTestId("preset-canal").click();
  await ready(page);
  await shot(page, "studio-canal-ink-desktop.png");
  await page.getByTestId("mode-diorama").click();

  page = await fresh(ctx, page, "/how");
  await settle(page, 6000);
  await shot(page, "how-it-grows-desktop.png");
  await ctx.close();
}

// mobile
{
  const ctx = await browser.newContext({ ...devices["Pixel 7"] });
  let page = null;
  page = await fresh(ctx, page, "/");
  await settle(page, 6000);
  await shot(page, "landing-mobile.png");
  page = await fresh(ctx, page, "/studio");
  await ready(page);
  await shot(page, "studio-mobile.png");
  await page.getByTestId("tab-params").click();
  await page.locator(".tabs").scrollIntoViewIfNeeded();
  await settle(page, 1500);
  await shot(page, "studio-panels-mobile.png");
  await ctx.close();
}
await browser.close();
