import { useEffect, useMemo, useRef } from "react";
import * as THREE from "three";
import type { ReplayModel } from "../lib/replay";
import { STATE_COLORS, visibleInReplay } from "../lib/palette";

interface Props {
  model: ReplayModel;
  frame: number;
  highlightFrame?: boolean;
}

const PALETTE = STATE_COLORS.map((c) => (c === "transparent" ? new THREE.Color(0, 0, 0) : new THREE.Color(c)));
const FRESH = new THREE.Color("#ffffff");

/**
 * Growth replay: the recorded semantic grid drawn as voxels. Only cells with an exposed face are
 * instanced (buried rock is skipped). Cells changed in the current frame flash lighter.
 */
export function ReplayVoxels({ model, frame, highlightFrame = true }: Props) {
  const ref = useRef<THREE.InstancedMesh>(null);
  const capacity = Math.min(model.size, 160_000);
  const geo = useMemo(() => new THREE.BoxGeometry(0.94, 0.94, 0.94), []);
  const mat = useMemo(() => new THREE.MeshStandardMaterial({ roughness: 0.8 }), []);
  const buf = useMemo(() => new Uint8Array(model.size), [model]);
  const fresh = useMemo(() => new Uint8Array(model.size), [model]);

  useEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    const s = model.stateAt(frame, buf);
    fresh.fill(0);
    if (highlightFrame && frame >= 0) {
      // mark cells written by this frame
      const diff = model.frameDiff(frame);
      if (diff) for (let k = 0; k < diff.length; k++) fresh[diff[k]] = 1;
    }
    const { sx, sy, sz } = model;
    const ox = -sx / 2 + 0.5, oz = -sz / 2 + 0.5;
    const m = new THREE.Matrix4();
    const c = new THREE.Color();
    let n = 0;
    const open = (x: number, y: number, z: number) => {
      if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return true;
      return !visibleInReplay(s[x + sx * (z + sz * y)]);
    };
    for (let y = 0; y < sy && n < capacity; y++)
      for (let z = 0; z < sz && n < capacity; z++)
        for (let x = 0; x < sx; x++) {
          const i = x + sx * (z + sz * y);
          const st = s[i];
          if (!visibleInReplay(st)) continue;
          if (!(open(x + 1, y, z) || open(x - 1, y, z) || open(x, y + 1, z) || open(x, y - 1, z) || open(x, y, z + 1) || open(x, y, z - 1))) continue;
          m.makeTranslation(ox + x, y + 0.5, oz + z);
          mesh.setMatrixAt(n, m);
          c.copy(PALETTE[st] ?? PALETTE[2]);
          if (fresh[i]) c.lerp(FRESH, 0.45);
          mesh.setColorAt(n, c);
          n++;
          if (n >= capacity) break;
        }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.computeBoundingSphere();
  }, [model, frame, buf, fresh, capacity, highlightFrame]);

  useEffect(() => () => {
    geo.dispose();
    mat.dispose();
  }, [geo, mat]);

  return <instancedMesh ref={ref} args={[geo, mat, capacity]} castShadow receiveShadow />;
}
