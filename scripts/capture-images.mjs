#!/usr/bin/env node
// Captures the gallery thumbnails and the "how it grows" stage images from the real precomputed
// designs (web/public/scenes/*), by rendering /render in headless Chromium with the actual viewer.
// Nothing is painted by hand: every image is a screenshot of engine output.
//
// Usage: node scripts/capture-images.mjs [baseUrl]   (default http://localhost:5173, run `npm run dev` in web/ first)
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(root, "web", "package.json"));
const { chromium } = require("@playwright/test");

const base = process.argv[2] ?? "http://localhost:5173";
const out = join(root, "web", "public");
mkdirSync(join(out, "thumbs"), { recursive: true });
mkdirSync(join(out, "how"), { recursive: true });

const presets = ["library", "cliffside", "gardens", "cathedral", "canal", "organic", "escher"];
const jobs = [];
for (const p of presets) {
  jobs.push({ url: `/render?preset=${p}&quality=high`, file: `thumbs/${p}-large.png`, w: 1600, h: 1000 });
  jobs.push({ url: `/render?preset=${p}&quality=medium`, file: `thumbs/${p}.png`, w: 256, h: 192 });
}
jobs.push({ url: "/render?preset=library&stage=circulation", file: "how/compose.png", w: 800, h: 600 });
jobs.push({ url: "/render?preset=library&stage=growth", file: "how/grow.png", w: 800, h: 600 });
jobs.push({ url: "/render?preset=library&stage=validation&mode=clay", file: "how/check.png", w: 800, h: 600 });
jobs.push({ url: "/render?preset=library&stage=detailing", file: "how/detail.png", w: 800, h: 600 });
jobs.push({ url: "/render?preset=library&refined=1&mode=clay", file: "how/refine.png", w: 800, h: 600 });
jobs.push({ url: "/render?preset=library", file: "how/render.png", w: 800, h: 600 });

const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
let failures = 0;
for (const j of jobs) {
  const page = await browser.newPage({ viewport: { width: j.w, height: j.h } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  try {
    await page.goto(base + j.url, { waitUntil: "load" });
    await page.waitForSelector('[data-ready="1"]', { timeout: 60_000 });
    await page.waitForTimeout(4000); // shadows, post-processing and the first cloud frames settle
    await page.screenshot({ path: join(out, j.file), animations: "disabled", timeout: 90_000 });
    console.log(`ok   ${j.file}${errors.length ? "  (page errors: " + errors.join("; ") + ")" : ""}`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${j.file}: ${e.message}`);
  }
  await page.close();
}
await browser.close();
process.exit(failures ? 1 : 0);
