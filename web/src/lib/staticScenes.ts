import { decodeScene } from "./decode";
import type { GenerationConfig, InspectorWire, RefinementWire, ReplayWire, Scene, SceneWire } from "./types";

/**
 * Precomputed designs shipped with the site (produced by the Java CLI, see scripts/build-examples).
 * They load instantly and carry their exact config, so any of them can be regenerated live.
 */
async function getJson<T>(url: string): Promise<{ data: T; bytes: number }> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`missing ${url} (${res.status})`);
  const text = await res.text();
  return { data: JSON.parse(text) as T, bytes: text.length };
}

export async function loadStaticScene(preset: string): Promise<Scene> {
  const { data, bytes } = await getJson<SceneWire>(`/scenes/${preset}/scene.json`);
  return decodeScene(data, bytes);
}

export async function loadStaticReplay(preset: string): Promise<ReplayWire> {
  return (await getJson<ReplayWire>(`/scenes/${preset}/replay.json`)).data;
}

export async function loadStaticInspector(preset: string): Promise<InspectorWire> {
  return (await getJson<InspectorWire>(`/scenes/${preset}/inspector.json`)).data;
}

export async function loadStaticConfig(preset: string): Promise<GenerationConfig> {
  return (await getJson<GenerationConfig>(`/scenes/${preset}/config.json`)).data;
}

export async function loadStaticRefinement(preset: string): Promise<RefinementWire | null> {
  try {
    return (await getJson<RefinementWire>(`/scenes/${preset}/refinement.json`)).data;
  } catch {
    return null;
  }
}

export async function loadStaticRefinedScene(preset: string): Promise<Scene | null> {
  try {
    const { data, bytes } = await getJson<SceneWire>(`/scenes/${preset}/refined-scene.json`);
    return decodeScene(data, bytes);
  } catch {
    return null;
  }
}
