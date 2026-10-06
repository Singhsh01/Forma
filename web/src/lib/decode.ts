import type { Scene, SceneWire } from "./types";

/** Decodes standard base64 into bytes (works in browsers and Node). */
export function b64ToBytes(b64: string): Uint8Array {
  if (typeof atob === "function") {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  return new Uint8Array(Buffer.from(b64, "base64"));
}

/** Little-endian float32 view (copies into an aligned buffer). */
export function b64ToF32(b64: string): Float32Array {
  const bytes = b64ToBytes(b64);
  const buf = new ArrayBuffer(bytes.length);
  new Uint8Array(buf).set(bytes);
  return new Float32Array(buf);
}

export function b64ToU16(b64: string): Uint16Array {
  const bytes = b64ToBytes(b64);
  const buf = new ArrayBuffer(bytes.length);
  new Uint8Array(buf).set(bytes);
  return new Uint16Array(buf);
}

export function b64ToI32(b64: string): Int32Array {
  const bytes = b64ToBytes(b64);
  const buf = new ArrayBuffer(bytes.length);
  new Uint8Array(buf).set(bytes);
  return new Int32Array(buf);
}

export const STRIDE = 9;

export function decodeScene(wire: SceneWire, bytes = 0): Scene {
  const { groups, ...rest } = wire;
  return {
    wire: rest,
    bytes,
    groups: groups.map((g) => {
      const data = b64ToF32(g.data);
      const comp = b64ToU16(g.comp);
      if (data.length !== g.count * STRIDE) throw new Error(`group ${g.kind}/${g.material}: expected ${g.count * STRIDE} floats, got ${data.length}`);
      if (comp.length !== g.count) throw new Error(`group ${g.kind}/${g.material}: expected ${g.count} component ids`);
      return { kind: g.kind, material: g.material, count: g.count, data, comp };
    }),
  };
}
