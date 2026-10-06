import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ReplayModel } from "../src/lib/replay";
import { b64ToBytes, b64ToI32 } from "../src/lib/decode";
import type { ReplayWire } from "../src/lib/types";

const wireOf = (preset: string) => JSON.parse(readFileSync(join(__dirname, "..", "public", "scenes", preset, "replay.json"), "utf8")) as ReplayWire;

/** Reference: apply every diff from an empty grid, no keyframes. */
function naive(w: ReplayWire, frame: number) {
  const s = new Uint8Array(w.grid.sx * w.grid.sy * w.grid.sz);
  for (let f = 0; f <= frame; f++) {
    const idx = b64ToI32(w.frames[f].idx), val = b64ToBytes(w.frames[f].val);
    for (let k = 0; k < idx.length; k++) s[idx[k]] = val[k];
  }
  return s;
}

describe("growth replay", () => {
  it("scrubbing through keyframes gives exactly the state of applying every diff", () => {
    for (const preset of ["escher", "canal"]) {
      const w = wireOf(preset);
      const m = new ReplayModel(w, 8);
      for (const f of [-1, 0, 3, 7, 8, 9, 15, Math.floor(m.frameCount / 2), m.frameCount - 1]) {
        if (f >= m.frameCount) continue;
        expect(m.stateAt(f)).toEqual(naive(w, f));
      }
    }
  });

  it("maps frames to their stages in order", () => {
    const m = new ReplayModel(wireOf("organic"));
    let last = -1;
    for (let f = 0; f < m.frameCount; f++) {
      expect(m.frameStage[f]).toBeGreaterThanOrEqual(last);
      last = m.frameStage[f];
    }
    expect(m.stageOfFrame(m.frameCount - 1)?.id).toBe(m.stages[last].id);
    expect(m.stageOfFrame(-1)).toBeUndefined();
  });

  it("refuses a corrupt replay instead of drawing garbage", () => {
    const w = wireOf("escher");
    const bad = { ...w, frames: [{ ...w.frames[0], idx: Buffer.from(new Uint8Array(new Int32Array([w.grid.sx * w.grid.sy * w.grid.sz + 5]).buffer)).toString("base64"), val: Buffer.from([3]).toString("base64") }] };
    expect(() => new ReplayModel(bad)).toThrow(/outside the grid/);
  });
});
