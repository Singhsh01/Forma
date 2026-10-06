import * as THREE from "three";
import type { RenderMode } from "../lib/types";

/**
 * Material palette per render mode. Diorama is the vivid exhibition look; clay strips colour to
 * study form; ink and blueprint use flat faces plus screen-space edge lines (see EdgeEffect).
 */
export const DIORAMA: Record<string, string> = {
  sandstone: "#e9d5b2",
  plaster: "#f2e6d3",
  shell: "#efe6d6",
  terracotta: "#d8714d",
  copper: "#4fb3a0",
  timber: "#a7704a",
  rock: "#6c6878",
  basalt: "#40455a",
  marble: "#efe9df",
  concrete: "#b9b2a6",
  brass: "#cfa65e",
  floor: "#b78656",
  paving: "#d4c19f",
  grass: "#6aa84f",
  foliage: "#3f8f4a",
  "foliage-alt": "#78b54a",
  "foliage-bloom": "#f08a72",
  deck: "#9b6942",
  canopy: "#4fb3a0",
  water: "#38a9c2",
  glass: "#a8dbe8",
  "glass-lit": "#ffcf7a",
  frame: "#3b2f2b",
  iron: "#2c2a2b",
  books: "#8e4a37",
  pier: "#c4b294",
  trunk: "#6b4a33",
  column: "#efe3cb",
  window: "#ffb547",
  lamp: "#ffd18c",
};

const WINDOW_WARM = new THREE.Color("#ffb547");
const WINDOW_HOT = new THREE.Color("#ffd27a");
const WINDOW_DIM = new THREE.Color("#2a3550");

const cache = new Map<string, THREE.Material>();

export function isEmissive(material: string) {
  return material === "window" || material === "lamp" || material === "glass-lit";
}

export function materialFor(mode: RenderMode, material: string): THREE.Material {
  const key = mode + "|" + material;
  const hit = cache.get(key);
  if (hit) return hit;
  let m: THREE.Material;
  if (mode === "diorama") {
    if (material === "glass-lit") {
      m = new THREE.MeshBasicMaterial({ color: "#ffffff", toneMapped: false, transparent: true, opacity: 0.82 });
    } else if (isEmissive(material)) {
      m = new THREE.MeshBasicMaterial({ color: "#ffffff", toneMapped: false });
    } else if (material === "water") {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA.water, roughness: 0.12, metalness: 0.2, transparent: true, opacity: 0.88 });
    } else if (material === "glass") {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA.glass, roughness: 0.05, metalness: 0.3, transparent: true, opacity: 0.55 });
    } else if (material === "copper") {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA.copper, roughness: 0.45, metalness: 0.35 });
    } else if (material === "brass") {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA.brass, roughness: 0.35, metalness: 0.6 });
    } else if (material.startsWith("foliage") || material === "grass") {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA[material], roughness: 0.9, flatShading: material !== "grass" });
    } else if (material === "books") {
      m = new THREE.MeshStandardMaterial({ color: "#ffffff", roughness: 0.85 });
    } else {
      m = new THREE.MeshStandardMaterial({ color: DIORAMA[material] ?? "#d8cfc0", roughness: material === "rock" ? 0.95 : 0.82 });
    }
  } else if (mode === "clay") {
    if (isEmissive(material)) m = new THREE.MeshStandardMaterial({ color: "#f3efe7", roughness: 1, emissive: "#ffe9c4", emissiveIntensity: 0.25 });
    else if (material === "water") m = new THREE.MeshStandardMaterial({ color: "#c9d3d6", roughness: 0.4 });
    else if (material.startsWith("foliage") || material === "grass") m = new THREE.MeshStandardMaterial({ color: "#d6d9cc", roughness: 1, flatShading: true });
    else if (material === "rock") m = new THREE.MeshStandardMaterial({ color: "#bdb7ad", roughness: 1 });
    else m = new THREE.MeshStandardMaterial({ color: "#ebe6dc", roughness: 1 });
  } else if (mode === "ink") {
    const tone = material === "rock" ? "#e9e4d8" : material.startsWith("foliage") ? "#efece2" : material === "water" ? "#dfe6e6" : "#fbf8f1";
    m = new THREE.MeshLambertMaterial({ color: tone });
  } else {
    // blueprint: faces in deep cyanotype blue, lighter for nearer planes via lambert shading
    const tone = material === "rock" ? "#1c4a78" : material === "water" ? "#22608f" : "#1f5687";
    m = new THREE.MeshLambertMaterial({ color: tone });
  }
  cache.set(key, m);
  return m;
}

/** Per-instance colour multiplier: subtle tint jitter, or brightness for lit windows. */
export function instanceTint(mode: RenderMode, material: string, variation: number, out: THREE.Color): THREE.Color {
  if (material === "window") {
    if (mode !== "diorama") return out.setScalar(1);
    if (variation < 0.36) return out.copy(WINDOW_DIM).multiplyScalar(0.8 + variation);
    if (variation > 0.86) return out.copy(WINDOW_HOT).multiplyScalar(1.5);
    return out.copy(WINDOW_WARM).multiplyScalar(0.95 + variation * 0.55);
  }
  if (material === "lamp") return mode === "diorama" ? out.set("#ffd18c").multiplyScalar(2.2) : out.setScalar(1);
  if (material === "glass-lit") {
    if (mode !== "diorama") return out.setScalar(1);
    const hues = ["#ffcf7a", "#ffb35c", "#ff9a6b", "#7fd6c8", "#ffe2a3"];
    return out.set(hues[Math.floor(variation * hues.length) % hues.length]).multiplyScalar(1.15);
  }
  if (mode !== "diorama") return out.setScalar(material === "rock" ? 0.96 + variation * 0.06 : 1);
  if (material === "books") {
    const hues = ["#8e4a37", "#2f5b6e", "#b58a3a", "#5d3f63", "#3f6b45"];
    return out.set(hues[Math.floor(variation * hues.length) % hues.length]);
  }
  const amp = material === "rock" ? 0.16 : material.startsWith("foliage") ? 0.22 : 0.07;
  return out.setScalar(1 - amp / 2 + variation * amp);
}

export function disposeMaterials() {
  cache.forEach((m) => m.dispose());
  cache.clear();
}
