import { expect, type Page } from "@playwright/test";

/** Collects console errors and uncaught page errors so a test can assert the page stayed clean. */
export function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
  page.on("console", (m) => {
    if (m.type() !== "error") return;
    const t = m.text();
    // failed network requests a test injects on purpose are logged by the browser itself
    if (/Failed to load resource/.test(t)) return;
    errors.push("console: " + t);
  });
  return errors;
}

export async function openStudio(page: Page, query = "") {
  await page.goto("/studio" + query);
  const stage = page.getByTestId("stage");
  await expect(stage).toHaveAttribute("data-preset", /.+/);
  return stage;
}

/** Waits until the engine is reachable from the studio (Generate enabled). */
export async function engineReady(page: Page) {
  await expect(page.getByTestId("generate")).toBeEnabled();
}

export async function generate(page: Page) {
  const stage = page.getByTestId("stage");
  const before = await stage.getAttribute("data-job");
  await page.getByTestId("generate").click();
  await expect(stage).not.toHaveAttribute("data-job", before ?? "");
  await expect(stage).toHaveAttribute("data-source", "live");
  await expect(page.getByTestId("job-overlay")).toHaveCount(0);
  return (await stage.getAttribute("data-job"))!;
}

export async function selectPreset(page: Page, id: string) {
  await page.getByTestId(`preset-${id}`).click();
  await expect(page.getByTestId("stage")).toHaveAttribute("data-preset", id);
}
