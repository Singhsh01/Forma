import { b64ToBytes, b64ToI32 } from "./decode";
import type { ReplayWire, StageInfo } from "./types";

/**
 * Recorded generation history, decoded once. Frames are sparse diffs (cell index -> new state)
 * applied from an all-empty grid. Keyframe snapshots every `keyEvery` frames make scrubbing to
 * any frame cheap: start from the nearest snapshot and apply at most keyEvery - 1 diffs.
 */
export class ReplayModel {
  readonly sx: number;
  readonly sy: number;
  readonly sz: number;
  readonly size: number;
  readonly stages: StageInfo[];
  readonly frameCount: number;
  readonly frameStage: Int32Array;
  readonly frameLabels: string[];
  private readonly idx: Int32Array[];
  private readonly val: Uint8Array[];
  private readonly keys = new Map<number, Uint8Array>();
  private readonly keyEvery: number;

  constructor(wire: ReplayWire, keyEvery = 24) {
    this.sx = wire.grid.sx;
    this.sy = wire.grid.sy;
    this.sz = wire.grid.sz;
    this.size = this.sx * this.sy * this.sz;
    this.stages = wire.stages;
    this.frameCount = wire.frames.length;
    this.frameStage = new Int32Array(wire.frames.map((f) => f.stage));
    this.frameLabels = wire.frames.map((f) => f.label);
    this.idx = wire.frames.map((f) => b64ToI32(f.idx));
    this.val = wire.frames.map((f) => b64ToBytes(f.val));
    this.idx.forEach((a, i) => {
      if (a.length !== this.val[i].length) throw new Error(`replay frame ${i} is corrupt`);
      for (let k = 0; k < a.length; k++) if (a[k] < 0 || a[k] >= this.size) throw new Error(`replay frame ${i} indexes outside the grid`);
    });
    this.keyEvery = Math.max(4, keyEvery);
    // build snapshots incrementally
    const s = new Uint8Array(this.size);
    for (let f = 0; f < this.frameCount; f++) {
      this.applyFrame(s, f);
      if (f % this.keyEvery === this.keyEvery - 1) this.keys.set(f, s.slice());
    }
  }

  private applyFrame(target: Uint8Array, f: number) {
    const a = this.idx[f], v = this.val[f];
    for (let k = 0; k < a.length; k++) target[a[k]] = v[k];
  }

  /** Cell states after frames [0..frame] have been applied (frame -1 = empty grid). */
  stateAt(frame: number, out?: Uint8Array): Uint8Array {
    const target = out ?? new Uint8Array(this.size);
    const f = Math.min(frame, this.frameCount - 1);
    let start = 0;
    target.fill(0);
    if (f >= 0) {
      const key = Math.floor((f + 1) / this.keyEvery) * this.keyEvery - 1;
      if (key >= 0 && this.keys.has(key)) {
        target.set(this.keys.get(key)!);
        start = key + 1;
      }
      for (let i = start; i <= f; i++) this.applyFrame(target, i);
    }
    return target;
  }

  stageOfFrame(frame: number): StageInfo | undefined {
    if (frame < 0) return undefined;
    return this.stages[this.frameStage[Math.min(frame, this.frameCount - 1)]];
  }

  /** Cell indices written by one frame. */
  frameDiff(frame: number): Int32Array | undefined {
    return this.idx[frame];
  }

  changesIn(frame: number) {
    return this.idx[frame]?.length ?? 0;
  }

  index(x: number, y: number, z: number) {
    return x + this.sx * (z + this.sz * y);
  }
}
