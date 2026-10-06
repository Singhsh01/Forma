import * as THREE from "three";
import { GLTFExporter } from "three/examples/jsm/exporters/GLTFExporter.js";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";

/** Triggers a browser download for a Blob. */
export function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export function slug(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "forma";
}

/**
 * Bakes every InstancedMesh under `root` into ordinary meshes (one merged mesh per material) so
 * the exported file opens in any glTF viewer, including those without EXT_mesh_gpu_instancing.
 * Coordinates are metres (1 cell = 1.5 m), y up.
 */
export function bakeForExport(root: THREE.Object3D, metresPerUnit = 1.5): THREE.Group {
  const out = new THREE.Group();
  out.name = "FORMA design";
  const byMaterial = new Map<THREE.Material, THREE.BufferGeometry[]>();
  const m = new THREE.Matrix4();
  const color = new THREE.Color();
  const scale = new THREE.Matrix4().makeScale(metresPerUnit, metresPerUnit, metresPerUnit);
  root.updateMatrixWorld(true);
  root.traverse((o) => {
    const im = o as THREE.InstancedMesh;
    if (!im.isInstancedMesh || im.count === 0) return;
    const mat = Array.isArray(im.material) ? im.material[0] : im.material;
    const base = im.geometry.index ? im.geometry.toNonIndexed() : im.geometry.clone();
    for (const key of Object.keys(base.attributes)) if (!["position", "normal"].includes(key)) base.deleteAttribute(key);
    const list = byMaterial.get(mat) ?? [];
    for (let i = 0; i < im.count; i++) {
      im.getMatrixAt(i, m);
      const g = base.clone();
      g.applyMatrix4(new THREE.Matrix4().multiplyMatrices(scale, m));
      if (im.instanceColor) {
        im.getColorAt(i, color);
        const n = g.getAttribute("position").count;
        const cols = new Float32Array(n * 3);
        for (let k = 0; k < n; k++) {
          cols[k * 3] = Math.min(1, color.r);
          cols[k * 3 + 1] = Math.min(1, color.g);
          cols[k * 3 + 2] = Math.min(1, color.b);
        }
        g.setAttribute("color", new THREE.BufferAttribute(cols, 3));
      }
      list.push(g);
    }
    byMaterial.set(mat, list);
    base.dispose();
  });
  let idx = 0;
  byMaterial.forEach((geoms, mat) => {
    // merge in chunks to keep individual meshes reasonable
    for (let start = 0; start < geoms.length; start += 4000) {
      const chunk = geoms.slice(start, start + 4000);
      const merged = mergeGeometries(chunk, false);
      chunk.forEach((g) => g.dispose());
      if (!merged) continue;
      const src = mat as THREE.MeshStandardMaterial;
      const em = new THREE.MeshStandardMaterial({
        color: src.color ? src.color.clone() : new THREE.Color("#cccccc"),
        roughness: src.roughness ?? 0.8,
        metalness: src.metalness ?? 0,
        vertexColors: merged.getAttribute("color") ? true : false,
        transparent: src.transparent,
        opacity: src.opacity,
        name: src.name || `material-${idx}`,
      });
      if ((mat as THREE.MeshBasicMaterial).isMeshBasicMaterial) {
        em.emissive = new THREE.Color("#ffb547");
        em.emissiveIntensity = 1;
      }
      const mesh = new THREE.Mesh(merged, em);
      mesh.name = `part-${idx++}`;
      out.add(mesh);
    }
  });
  return out;
}

export async function exportGlb(root: THREE.Object3D): Promise<Blob> {
  const baked = bakeForExport(root);
  const exporter = new GLTFExporter();
  const result = await exporter.parseAsync(baked, { binary: true, onlyVisible: true });
  baked.traverse((o) => {
    const mesh = o as THREE.Mesh;
    if (mesh.isMesh) {
      mesh.geometry.dispose();
      (mesh.material as THREE.Material).dispose();
    }
  });
  return new Blob([result as ArrayBuffer], { type: "model/gltf-binary" });
}
