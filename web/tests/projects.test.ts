import { describe, expect, it } from "vitest";
import { parseProjectFile, projectFile, storageAvailable, listProjects, saveProject } from "../src/lib/projects";

const cfg = { preset: "canal", seed: 42, params: { size: 10, canals: 1.2 }, ruleOverrides: { plan: "sequence plan" }, generatorVersion: "forma-engine/0.1.0" };
const refine = { iterations: 1200, temperature: 0.3, temperatureEnd: 0.02, mode: "anneal" as const, weights: { daylight: 2 }, mcmcSeed: 9, keep: "final" as const };

describe("project files", () => {
  it("round-trips config, refinement settings and view", () => {
    const file = projectFile("Harbour study", cfg, refine, { renderMode: "ink", projection: "orthographic" });
    const back = parseProjectFile(JSON.stringify(file));
    expect(back.config).toEqual({ ...cfg });
    expect(back.refine).toEqual(refine);
    expect(back.view).toEqual({ renderMode: "ink", projection: "orthographic" });
    expect(back.name).toBe("Harbour study");
  });

  it("fills sensible defaults for optional fields of older files", () => {
    const back = parseProjectFile(JSON.stringify({ format: "forma-project/1", config: { preset: "library", seed: 7.9 }, refine: { iterations: 100, temperature: 0.2 } }));
    expect(back.config.seed).toBe(7);
    expect(back.config.params).toEqual({});
    expect(back.refine?.keep).toBe("best");
    expect(back.refine?.mode).toBe("mcmc");
    expect(back.refine?.temperatureEnd).toBe(0.2);
  });

  it("explains what is wrong with a bad file", () => {
    expect(() => parseProjectFile("{nope")).toThrow(/not valid JSON/);
    expect(() => parseProjectFile(JSON.stringify({ format: "other" }))).toThrow(/not a FORMA project/);
    expect(() => parseProjectFile(JSON.stringify({ format: "forma-project/1", config: { seed: 1 } }))).toThrow(/no preset/);
    expect(() => parseProjectFile(JSON.stringify({ format: "forma-project/1", config: { preset: "x", seed: "1" } }))).toThrow(/numeric seed/);
    expect(() => parseProjectFile(JSON.stringify({ format: "forma-project/1", config: { preset: "x", seed: 1, params: { a: "tall" } } }))).toThrow(/Parameter "a"/);
    expect(() => parseProjectFile(JSON.stringify({ format: "forma-project/1", config: { preset: "x", seed: 1, ruleOverrides: { g: 3 } } }))).toThrow(/not text/);
  });

  it("degrades gracefully without browser storage", () => {
    // the test environment is Node: there is no localStorage at all
    expect(storageAvailable()).toBe(false);
    expect(listProjects()).toEqual([]);
    expect(saveProject({ name: "x", config: cfg, refine: null, favorite: false })).toBeNull();
  });
});
