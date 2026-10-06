import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { engineReady, generate, openStudio, selectPreset, watchErrors } from "./helpers";

test.describe("studio against the live Java engine", () => {
  test("1. loads the flagship and generates it live with progress", async ({ page }) => {
    const errors = watchErrors(page);
    const stage = await openStudio(page);
    await expect(stage).toHaveAttribute("data-source", "static");
    await engineReady(page);
    const sawProgress = page.getByTestId("job-overlay").waitFor({ state: "visible" }).then(() => true, () => false);
    const job = await generate(page);
    expect(job).toMatch(/^gen-/);
    expect(await sawProgress).toBe(true);
    expect(Number(await stage.getAttribute("data-instances"))).toBeGreaterThan(1000);
    expect(errors).toEqual([]);
  });

  test("2. a parameter change changes the geometry; the same settings reproduce it exactly", async ({ page, request }) => {
    const stage = await openStudio(page);
    await engineReady(page);
    await selectPreset(page, "escher");
    const a = await generate(page);
    const levels = page.getByTestId("param-levels");
    await levels.fill("3");
    await expect(page.getByTestId("generate")).toHaveText(/Generate/);
    const b = await generate(page);
    expect(b).not.toBe(a);
    await levels.fill("5");
    const c = await generate(page);
    const sceneOf = async (id: string) => {
      const s = await (await request.get(`http://127.0.0.1:8080/api/jobs/${id}/scene`)).json();
      return JSON.stringify(s.groups);
    };
    // escher defaults to 5 levels: generation c repeats generation a byte for byte
    expect(await sceneOf(c)).toBe(await sceneOf(a));
    expect(await sceneOf(b)).not.toBe(await sceneOf(a));
    await expect(stage).toHaveAttribute("data-preset", "escher");
  });

  test("3. seed field, randomize and reproducibility metadata", async ({ page }) => {
    const stage = await openStudio(page);
    await engineReady(page);
    await selectPreset(page, "canal");
    const seed = page.locator(".seed-field input");
    await seed.fill("1234");
    await generate(page);
    await expect(stage).toHaveAttribute("data-seed", "1234");
    await page.getByTestId("tab-export").click();
    const meta = page.locator(".meta-list");
    await expect(meta).toContainText("canal");
    await expect(meta).toContainText("1234");
    await expect(meta).toContainText("forma-engine/");
    await page.getByRole("button", { name: "New random seed" }).click();
    await expect(seed).not.toHaveValue("1234");
  });

  test("4. cancel stops waiting and keeps the previous design", async ({ page }) => {
    const stage = await openStudio(page);
    await engineReady(page);
    const job = await stage.getAttribute("data-job");
    // hold the progress stream open so the job is still in flight when Cancel is pressed
    await page.route("**/api/jobs/*/events", () => {});
    await page.route("**/api/jobs/gen-*", (r) => (r.request().method() === "GET" ? r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ jobId: "x", type: "generate", status: "running", stage: "masses", fraction: 0.3, message: "Major masses", preset: "library", seed: 7, createdAt: Date.now() }) }) : r.continue()));
    await page.getByTestId("generate").click();
    await expect(page.getByTestId("job-overlay")).toBeVisible();
    const cancelled = page.waitForRequest((r) => r.method() === "DELETE" && /\/api\/jobs\/gen-/.test(r.url()));
    await page.getByTestId("cancel").click();
    await cancelled;
    await expect(page.getByTestId("job-overlay")).toHaveCount(0);
    await expect(stage).toHaveAttribute("data-job", job ?? "");
    await expect(stage).toHaveAttribute("data-preset", "library");
  });

  test("5. growth replay: play, pause, step and scrub", async ({ page }) => {
    await openStudio(page);
    await page.getByTestId("replay-toggle").click();
    const status = page.locator(".timeline-status");
    await expect(status).toContainText(/frame \d+\/\d+/);
    await page.getByTestId("replay-scrub").fill("0");
    await expect(status).toContainText("frame 1/");
    await page.getByTestId("replay-next").click();
    await expect(status).toContainText("frame 2/");
    await page.getByTestId("replay-play").click();
    await expect.poll(async () => Number((await status.textContent())!.match(/frame (\d+)\//)![1]), { timeout: 15_000 }).toBeGreaterThan(4);
    await page.getByTestId("replay-pause").click();
    const paused = await status.textContent();
    await page.waitForTimeout(800);
    await expect(status).toHaveText(paused!);
    await page.getByTestId("replay-toggle").click();
    await expect(status).toHaveCount(0);
  });

  test("6. rule inspector shows programs and applications; a broken edit is refused with its line", async ({ page }) => {
    await openStudio(page);
    await engineReady(page);
    await page.getByTestId("tab-rules").click();
    await expect(page.locator(".program").first()).toBeVisible();
    await page.getByRole("button", { name: "Edit rules" }).first().click();
    const editor = page.locator("textarea.code-edit").first();
    await editor.fill("sequence broken\n  prl x\n    rule bad \"WW\" -> \"W\"");
    await page.getByRole("button", { name: "Validate and use" }).click();
    await expect(page.locator(".program-status.is-error")).toContainText(/line \d+/);
  });

  test("7. constraint checks list real results", async ({ page }) => {
    await openStudio(page);
    await page.getByTestId("tab-checks").click();
    await expect(page.getByText("Circulation reaches every required space").first()).toBeVisible();
  });

  test("8. refinement runs MCMC, reports energies and compares before and after", async ({ page }) => {
    const stage = await openStudio(page);
    await engineReady(page);
    await selectPreset(page, "organic");
    await generate(page);
    await page.getByTestId("tab-refine").click();
    await page.getByTestId("refine-iterations").fill("600");
    await page.getByTestId("run-refine").click();
    await expect(stage).toHaveAttribute("data-refined", "1", { timeout: 60_000 });
    await expect(page.locator(".refine-results")).toContainText("Accepted");
    await expect(page.locator(".energy-table tbody tr").first()).toBeVisible();
    // the comparison opens by itself after a refinement; open it if it was closed
    const open = page.getByRole("button", { name: /Compare before and after/ });
    if (await open.count()) await open.click();
    await page.getByTestId("compare-before").click();
    await expect(stage).toHaveAttribute("data-showing", "before");
    await page.getByTestId("compare-after").click();
    await expect(stage).toHaveAttribute("data-showing", "current");
  });

  test("9. exports PNG, GLB and a project file that imports back into the same design", async ({ page }) => {
    const stage = await openStudio(page);
    await engineReady(page);
    await selectPreset(page, "cathedral");
    await page.locator(".seed-field input").fill("77");
    await generate(page);
    await page.getByTestId("tab-export").click();

    const [png] = await Promise.all([page.waitForEvent("download"), page.getByTestId("export-png").click()]);
    const pngBytes = readFileSync((await png.path())!);
    expect(pngBytes.subarray(1, 4).toString()).toBe("PNG");
    expect(pngBytes.length).toBeGreaterThan(20_000);

    const [glb] = await Promise.all([page.waitForEvent("download", { timeout: 90_000 }), page.getByTestId("export-glb").click()]);
    const g = readFileSync((await glb.path())!);
    expect(g.subarray(0, 4).toString()).toBe("glTF");
    expect(g.readUInt32LE(4)).toBe(2);
    expect(g.readUInt32LE(8)).toBe(g.length);
    const jsonLen = g.readUInt32LE(12);
    const gltf = JSON.parse(g.subarray(20, 20 + jsonLen).toString());
    expect(gltf.meshes.length).toBeGreaterThan(5);
    expect(gltf.asset.version).toBe("2.0");

    const [proj] = await Promise.all([page.waitForEvent("download"), page.getByTestId("export-json").click()]);
    const file = JSON.parse(readFileSync((await proj.path())!, "utf8"));
    expect(file.format).toBe("forma-project/1");
    expect(file.config).toMatchObject({ preset: "cathedral", seed: 77 });

    // switch away, then import the file: the studio regenerates the identical design
    await selectPreset(page, "library");
    await page.getByTestId("tab-export").click();
    await page.getByTestId("import-input").setInputFiles({ name: "cathedral.forma.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(file)) });
    await expect(stage).toHaveAttribute("data-preset", "cathedral");
    await expect(stage).toHaveAttribute("data-seed", "77");
    await expect(stage).toHaveAttribute("data-source", "live");
  });

  test("10. an engine error is explained and the last design stays on screen", async ({ page }) => {
    const errors = watchErrors(page);
    const stage = await openStudio(page);
    await engineReady(page);
    const job = await stage.getAttribute("data-job");
    const instances = await stage.getAttribute("data-instances");
    await page.route("**/api/jobs/generate", (r) => r.fulfill({ status: 429, contentType: "application/json", body: JSON.stringify({ error: "the generator queue is full; try again shortly" }) }));
    await page.getByTestId("generate").click();
    await expect(page.getByTestId("error-toast")).toContainText("queue is full");
    await expect(stage).toHaveAttribute("data-job", job ?? "");
    await expect(stage).toHaveAttribute("data-instances", instances ?? "");
    await expect(page.locator(".viewport canvas")).toBeVisible();
    expect(errors).toEqual([]);
  });

  test("11. without the engine the studio still shows precomputed designs and says why it cannot generate", async ({ page }) => {
    await page.route("**/api/**", (r) => r.abort());
    const stage = await openStudio(page);
    await expect(page.locator(".topbar-hint")).toContainText("Engine offline");
    await expect(page.getByTestId("generate")).toBeDisabled();
    await selectPreset(page, "gardens");
    await expect(stage).toHaveAttribute("data-source", "static");
  });

  test("12. saved projects and favourites persist in this browser", async ({ page }) => {
    await openStudio(page);
    await page.getByTestId("tab-export").click();
    await page.locator("#save-name").fill("Library study");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".project-name")).toContainText(["Library study"]);
    await page.getByRole("button", { name: "Add to favourites" }).first().click();
    await page.reload();
    await page.getByTestId("tab-export").click();
    await expect(page.getByRole("button", { name: "Remove from favourites" }).first()).toBeVisible();
  });

  test("13. render modes and isometric projection switch the viewer", async ({ page }) => {
    const errors = watchErrors(page);
    await openStudio(page);
    for (const m of ["clay", "ink", "blueprint", "diorama"]) {
      await page.getByTestId(`mode-${m}`).click();
      await expect(page.getByTestId(`mode-${m}`)).toHaveAttribute("aria-checked", "true");
    }
    await selectPreset(page, "escher");
    // the labyrinth is composed for the isometric view and opens in it
    await expect(page.getByTestId("toggle-projection")).toHaveAttribute("aria-pressed", "true");
    expect(errors).toEqual([]);
  });

  test("14. every one of the seven worlds generates live in the engine", async ({ page }) => {
    const errors = watchErrors(page);
    await openStudio(page);
    await engineReady(page);
    for (const id of ["library", "cliffside", "gardens", "cathedral", "canal", "organic", "escher"]) {
      if (id !== "library") await selectPreset(page, id);
      await generate(page);
      expect(Number(await page.getByTestId("stage").getAttribute("data-instances")), id).toBeGreaterThan(200);
    }
    expect(errors).toEqual([]);
  });
});
