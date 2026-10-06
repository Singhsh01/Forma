import { expect, test } from "@playwright/test";
import { watchErrors } from "./helpers";

test("landing and studio fit a phone without horizontal scrolling", async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto("/");
  await expect(page.getByTestId("enter-studio").first()).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  await page.goto("/studio");
  await expect(page.getByTestId("stage")).toHaveAttribute("data-preset", "library");
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  await expect(page.getByTestId("generate")).toBeVisible();
  await page.getByTestId("tab-export").click();
  await expect(page.getByTestId("export-png")).toBeVisible();
  expect(errors).toEqual([]);
});
