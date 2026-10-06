import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { STRIDE, b64ToF32, b64ToI32, decodeScene } from "../src/lib/decode";
import type { SceneWire } from "../src/lib/types";

const scenes = join(__dirname, "..", "public", "scenes");
const read = (preset: string, file: string) => JSON.parse(readFileSync(join(scenes, preset, file), "utf8"));

describe("scene decoding", () => {
  it("decodes every shipped example into well-formed instance groups", () => {
    for (const preset of ["library", "cliffside", "gardens", "cathedral", "canal", "organic", "escher"]) {
      const wire = read(preset, "scene.json") as SceneWire;
      const scene = decodeScene(wire);
      expect(scene.groups.length).toBeGreaterThan(0);
      let total = 0;
      for (const g of scene.groups) {
        expect(g.data.length).toBe(g.count * STRIDE);
        expect(g.comp.length).toBe(g.count);
        for (let i = 0; i < g.count; i++) {
          // sizes are positive and finite
          for (let k = 3; k < 6; k++) expect(g.data[i * STRIDE + k]).toBeGreaterThan(0);
          for (let k = 0; k < STRIDE; k++) expect(Number.isFinite(g.data[i * STRIDE + k])).toBe(true);
        }
        total += g.count;
      }
      expect(total).toBe(wire.groups.reduce((s, g) => s + g.count, 0));
      expect(scene.wire.config.preset).toBe(preset);
    }
  });

  it("rejects a group whose payload does not match its count", () => {
    const wire = read("escher", "scene.json") as SceneWire;
    const broken = { ...wire, groups: [{ ...wire.groups[0], count: wire.groups[0].count + 1 }] };
    expect(() => decodeScene(broken)).toThrow(/expected/);
  });

  it("reads little-endian base64 numbers", () => {
    const f = new Float32Array([1.5, -2.25, 1e6]);
    const b64 = Buffer.from(new Uint8Array(f.buffer)).toString("base64");
    expect(Array.from(b64ToF32(b64))).toEqual([1.5, -2.25, 1e6]);
    const i = new Int32Array([0, 7, 123456789]);
    expect(Array.from(b64ToI32(Buffer.from(new Uint8Array(i.buffer)).toString("base64")))).toEqual([0, 7, 123456789]);
  });
});
