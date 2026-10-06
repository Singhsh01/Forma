import type { GenerationConfig, ProjectFile, RefineSettings, RenderMode } from "./types";

/**
 * Saved projects and favourites live in localStorage on this browser only. Every access is
 * guarded: private windows or blocked storage simply disable saving instead of breaking the studio.
 */
export interface SavedProject {
  id: string;
  name: string;
  savedAt: string;
  config: GenerationConfig;
  refine: RefineSettings | null;
  favorite: boolean;
  thumbnail?: string;
}

const KEY = "forma.projects.v1";

export function storageAvailable(): boolean {
  try {
    const k = "__forma_probe__";
    localStorage.setItem(k, "1");
    localStorage.removeItem(k);
    return true;
  } catch {
    return false;
  }
}

export function listProjects(): SavedProject[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const list = JSON.parse(raw) as SavedProject[];
    return Array.isArray(list) ? list.filter((p) => p && p.config && typeof p.config.preset === "string") : [];
  } catch {
    return [];
  }
}

function write(list: SavedProject[]): boolean {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, 60)));
    return true;
  } catch {
    // quota: drop thumbnails and retry once
    try {
      localStorage.setItem(KEY, JSON.stringify(list.slice(0, 60).map((p) => ({ ...p, thumbnail: undefined }))));
      return true;
    } catch {
      return false;
    }
  }
}

export function saveProject(p: Omit<SavedProject, "id" | "savedAt"> & { id?: string }): SavedProject | null {
  const list = listProjects();
  const entry: SavedProject = { ...p, id: p.id ?? `p${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`, savedAt: new Date().toISOString() };
  const next = [entry, ...list.filter((x) => x.id !== entry.id)];
  return write(next) ? entry : null;
}

export function deleteProject(id: string): boolean {
  return write(listProjects().filter((p) => p.id !== id));
}

export function toggleFavorite(id: string): boolean {
  return write(listProjects().map((p) => (p.id === id ? { ...p, favorite: !p.favorite } : p)));
}

export function projectFile(name: string, config: GenerationConfig, refine: RefineSettings | null, view: { renderMode: RenderMode; projection: "perspective" | "orthographic" }): ProjectFile {
  return {
    format: "forma-project/1",
    name,
    savedAt: new Date().toISOString(),
    generatorVersion: config.generatorVersion ?? "unknown",
    config: { ...config, ruleOverrides: { ...config.ruleOverrides } },
    refine,
    view,
  };
}

/** Validates an imported project file and returns a clean copy, or throws a readable error. */
export function parseProjectFile(text: string): ProjectFile {
  let o: unknown;
  try {
    o = JSON.parse(text);
  } catch {
    throw new Error("This file is not valid JSON.");
  }
  const p = o as Partial<ProjectFile>;
  if (!p || p.format !== "forma-project/1") throw new Error("This is not a FORMA project file (expected format forma-project/1).");
  const c = p.config as Partial<GenerationConfig> | undefined;
  if (!c || typeof c.preset !== "string") throw new Error("The project file has no preset.");
  if (typeof c.seed !== "number" || !Number.isFinite(c.seed)) throw new Error("The project file has no numeric seed.");
  const params: Record<string, number> = {};
  for (const [k, v] of Object.entries(c.params ?? {})) {
    if (typeof v !== "number" || !Number.isFinite(v)) throw new Error(`Parameter "${k}" is not a number.`);
    params[k] = v;
  }
  const ruleOverrides: Record<string, string> = {};
  for (const [k, v] of Object.entries(c.ruleOverrides ?? {})) {
    if (typeof v !== "string") throw new Error(`Rule override "${k}" is not text.`);
    ruleOverrides[k] = v;
  }
  let refine: RefineSettings | null = null;
  if (p.refine) {
    const r = p.refine;
    if (typeof r.iterations !== "number" || typeof r.temperature !== "number") throw new Error("The refinement settings are incomplete.");
    refine = {
      iterations: r.iterations,
      temperature: r.temperature,
      temperatureEnd: typeof r.temperatureEnd === "number" ? r.temperatureEnd : r.temperature,
      mode: r.mode === "anneal" ? "anneal" : "mcmc",
      weights: { ...(r.weights ?? {}) },
      mcmcSeed: typeof r.mcmcSeed === "number" ? r.mcmcSeed : 1,
      keep: r.keep === "final" ? "final" : "best",
    };
  }
  return {
    format: "forma-project/1",
    name: typeof p.name === "string" ? p.name : "Imported project",
    savedAt: typeof p.savedAt === "string" ? p.savedAt : new Date().toISOString(),
    generatorVersion: typeof p.generatorVersion === "string" ? p.generatorVersion : "unknown",
    config: { preset: c.preset, seed: Math.trunc(c.seed), params, ruleOverrides, generatorVersion: c.generatorVersion },
    refine,
    view: p.view,
  };
}
