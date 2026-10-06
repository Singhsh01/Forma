#!/usr/bin/env node
// Records a short feature tour of the running app (default http://localhost:5173) as a WebM video
// with Playwright. Usage: node scripts/record-feature-video.mjs [baseUrl] [outDir]
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { mkdirSync, renameSync } from "node:fs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(root, "web", "package.json"));
const { chromium } = require("@playwright/test");
const base = process.argv[2] ?? "http://localhost:5173";
const outDir = process.argv[3] ?? join(root, "docs", "video");
mkdirSync(outDir, { recursive: true });

const size = { width: 1280, height: 800 };
const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
const ctx = await browser.newContext({ viewport: size, recordVideo: { dir: outDir, size } });
const page = await ctx.newPage();
const pause = (ms) => page.waitForTimeout(ms);
const tab = (name) => page.getByRole("tab", { name }).first().click().catch(() => page.getByText(name, { exact: true }).first().click());

// landing
await page.goto(base + "/");
await pause(5000);
await page.mouse.wheel(0, 900);
await pause(2500);
await page.mouse.wheel(0, 900);
await pause(2500);

// studio: flagship, live generation
await page.goto(base + "/studio");
await page.getByTestId("generate").waitFor();
await pause(4000);
await page.getByTestId("generate").click();
await pause(5000);

// growth replay
await page.getByText("Replay growth").first().click().catch(() => {});
await pause(7000);
await page.keyboard.press("Escape").catch(() => {});

// worlds tour
for (const id of ["cliffside", "canal", "organic", "cathedral", "escher"]) {
  await page.getByTestId(`preset-${id}`).click();
  await pause(3500);
}

// render modes on the labyrinth (isometric)
for (const m of ["clay", "ink", "blueprint", "diorama"]) {
  await page.getByTestId(`mode-${m}`).click();
  await pause(1800);
}

// rules and checks
await page.getByTestId("preset-library").click();
await pause(2500);
await tab("Rules");
await pause(3500);
await tab("Checks");
await pause(3500);

// MCMC refinement
await tab("Refine");
await pause(1500);
await page.getByTestId("run-refine").click();
await pause(7000);
await page.mouse.wheel(0, 600);
await pause(3000);

const video = page.video();
await ctx.close();
await browser.close();
const file = join(outDir, "forma-feature-tour.webm");
renameSync(await video.path(), file);
console.log(file);
