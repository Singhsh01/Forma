import { expect, test } from "@playwright/test";
import { watchErrors } from "./helpers";

test("landing shows the live flagship, a gallery of real renders, and enters the studio", async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto("/");
  await expect(page).toHaveTitle(/FORMA/);
  // the hero renders the flagship design live in WebGL
  await expect(page.locator(".hero canvas, header canvas, canvas").first()).toBeVisible();
  // every other world in the gallery has a thumbnail that actually loaded (the flagship is the live hero)
  const imgs = page.locator('img[src^="/thumbs/"]');
  await expect(imgs).toHaveCount(6);
  for (let i = 0; i < 6; i++) {
    const img = imgs.nth(i);
    await img.scrollIntoViewIfNeeded();
    await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth)).toBeGreaterThan(100);
  }
  // the six growth steps use captured stage images
  const steps = page.locator('img[src^="/how/"]');
  await expect(steps).toHaveCount(6);
  await page.getByTestId("enter-studio").first().click();
  await page.waitForURL("**/studio");
  await expect(page.getByTestId("stage")).toHaveAttribute("data-preset", "library");
  expect(errors).toEqual([]);
});

test("the how-it-grows page replays a recorded generation", async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto("/how");
  await expect(page.locator("h1")).toBeVisible();
  await expect(page.locator(".how-stage-caption")).toContainText(/frame \d+ of \d+/, { timeout: 30_000 });
  await expect(page.locator(".how-code").first()).toContainText("rule");
  expect(errors).toEqual([]);
});

test("unknown routes show a real not-found page with a way back", async ({ page }) => {
  await page.goto("/no-such-place");
  await expect(page.getByRole("link", { name: /studio|exhibition|home/i }).first()).toBeVisible();
});
