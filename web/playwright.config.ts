import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests drive the real web app against the real Java engine (no mocks, except where a
 * test deliberately injects a failure). Both servers are started automatically unless already running.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 120_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    acceptDownloads: true,
    launchOptions: {
      // software WebGL so the 3D viewer renders on machines and CI runners without a GPU
      args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
    },
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } }, testIgnore: /mobile\.spec\.ts/ },
    { name: "mobile", use: { ...devices["Pixel 7"] }, testMatch: /mobile\.spec\.ts/ },
  ],
  webServer: [
    { command: "bash ../scripts/serve-engine.sh", url: "http://127.0.0.1:8080/api/health", reuseExistingServer: true, timeout: 180_000 },
    { command: "npm run dev -- --port 5173 --strictPort", url: "http://localhost:5173", reuseExistingServer: true, timeout: 120_000 },
  ],
});
