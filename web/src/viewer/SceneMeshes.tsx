import { useEffect, useMemo } from "react";
import * as THREE from "three";
import type { ThreeEvent } from "@react-three/fiber";
import type { RenderMode, Scene } from "../lib/types";
import { STRIDE } from "../lib/decode";
import { geometryFor } from "./geometries";
import { instanceTint, isEmissive, materialFor } from "./materials";

const SELECT = new THREE.Color("#3fe0cf");
const NO_SHADOW_KINDS = new Set(["pane", "lamp"]);

interface Props {
  scene: Scene;
  mode: RenderMode;
  selected: number | null;
  onSelect?: (component: number | null) => void;
  shadows?: boolean;
}

/** One InstancedMesh per (kind, material) group: a handful of draw calls for the whole design. */
export function SceneMeshes({ scene, mode, selected, onSelect, shadows = true }: Props) {
  const meshes = useMemo(() => {
    const m4 = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const pos = new THREE.Vector3();
    const scl = new THREE.Vector3();
    const euler = new THREE.Euler(0, 0, 0, "YXZ");
    const col = new THREE.Color();
    return scene.groups.map((g, gi) => {
      const mesh = new THREE.InstancedMesh(geometryFor(g.kind), materialFor(mode, g.material), g.count);
      const d = g.data;
      for (let i = 0; i < g.count; i++) {
        const o = i * STRIDE;
        pos.set(d[o], d[o + 1], d[o + 2]);
        scl.set(d[o + 3], d[o + 4], d[o + 5]);
        euler.set(d[o + 7], d[o + 6], 0, "YXZ");
        q.setFromEuler(euler);
        m4.compose(pos, q, scl);
        mesh.setMatrixAt(i, m4);
        mesh.setColorAt(i, instanceTint(mode, g.material, d[o + 8], col));
      }
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
      const big = g.kind === "box" || g.kind === "stair" || g.kind === "arch" || g.kind === "foliage" || g.kind === "cyl";
      mesh.castShadow = shadows && big && !NO_SHADOW_KINDS.has(g.kind) && mode !== "blueprint";
      mesh.receiveShadow = shadows && !isEmissive(g.material) && mode !== "blueprint";
      mesh.computeBoundingSphere();
      mesh.userData = { group: gi };
      mesh.name = `${g.kind}:${g.material}`;
      return mesh;
    });
  }, [scene, mode, shadows]);

  // highlight the selected component by recolouring its instances
  useEffect(() => {
    const col = new THREE.Color();
    meshes.forEach((mesh, gi) => {
      const g = scene.groups[gi];
      let touched = false;
      for (let i = 0; i < g.count; i++) {
        const isSel = selected !== null && g.comp[i] === selected;
        instanceTint(mode, g.material, g.data[i * STRIDE + 8], col);
        if (isSel) col.lerp(SELECT, 0.55);
        mesh.setColorAt(i, col);
        touched = true;
      }
      if (touched && mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    });
  }, [meshes, selected, scene, mode]);

  useEffect(() => () => meshes.forEach((m) => m.dispose()), [meshes]);

  const handle = (e: ThreeEvent<PointerEvent>) => {
    if (!onSelect) return;
    if (e.delta > 4) return; // a drag, not a click
    e.stopPropagation();
    const gi = (e.object.userData as { group?: number }).group;
    if (gi === undefined || e.instanceId === undefined) return;
    const comp = scene.groups[gi].comp[e.instanceId];
    onSelect(comp === selected ? null : comp);
  };

  return (
    <group onClick={handle}>
      {meshes.map((m) => (
        <primitive key={m.uuid} object={m} />
      ))}
    </group>
  );
}
