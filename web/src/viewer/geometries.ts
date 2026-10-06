import * as THREE from "three";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";

/**
 * Unit geometries for every primitive kind the engine emits. Each fits a 1x1x1 cell centred on
 * the origin; instances scale and rotate them. Built once and shared by every scene.
 */
let cache: Map<string, THREE.BufferGeometry> | null = null;

function stairGeometry(): THREE.BufferGeometry {
  // four steps rising toward +x across one cell, solid underneath (a stair block)
  const parts: THREE.BufferGeometry[] = [];
  const n = 4;
  for (let i = 0; i < n; i++) {
    const h = (i + 1) / n;
    const g = new THREE.BoxGeometry(1 / n, h, 1);
    g.translate(-0.5 + (i + 0.5) / n, -0.5 + h / 2, 0);
    parts.push(g);
  }
  const merged = mergeGeometries(parts, false)!;
  parts.forEach((p) => p.dispose());
  return merged;
}

function foliageGeometry(): THREE.BufferGeometry {
  const g = new THREE.IcosahedronGeometry(0.5, 1);
  const pos = g.getAttribute("position") as THREE.BufferAttribute;
  // gentle deterministic lumps so crowns read as foliage, not spheres
  const v = new THREE.Vector3();
  for (let i = 0; i < pos.count; i++) {
    v.fromBufferAttribute(pos, i);
    const k = 1 + 0.12 * Math.sin(v.x * 9.1 + v.y * 4.3) * Math.cos(v.z * 7.7 - v.y * 3.1);
    v.multiplyScalar(k);
    if (v.y < -0.2) v.y = -0.2 + (v.y + 0.2) * 0.45; // flatter underside
    pos.setXYZ(i, v.x, v.y, v.z);
  }
  g.computeVertexNormals();
  return g;
}

function archGeometry(): THREE.BufferGeometry {
  // a wall block with a round-headed opening through it (opening along x)
  const s = new THREE.Shape();
  s.moveTo(-0.5, -0.5);
  s.lineTo(0.5, -0.5);
  s.lineTo(0.5, 0.5);
  s.lineTo(-0.5, 0.5);
  s.lineTo(-0.5, -0.5);
  const hole = new THREE.Path();
  const r = 0.3;
  hole.moveTo(-r, -0.5);
  hole.lineTo(-r, 0.08);
  hole.absarc(0, 0.08, r, Math.PI, 0, true);
  hole.lineTo(r, -0.5);
  hole.lineTo(-r, -0.5);
  s.holes.push(hole);
  const g = new THREE.ExtrudeGeometry(s, { depth: 1, bevelEnabled: false, curveSegments: 10 });
  g.translate(0, 0, -0.5);
  g.rotateY(Math.PI / 2);
  return g;
}

export function geometryFor(kind: string): THREE.BufferGeometry {
  if (!cache) cache = new Map();
  let g = cache.get(kind);
  if (g) return g;
  switch (kind) {
    case "cyl":
      g = new THREE.CylinderGeometry(0.5, 0.5, 1, 12, 1);
      break;
    case "stair":
      g = stairGeometry();
      break;
    case "foliage":
      g = foliageGeometry();
      break;
    case "lamp":
    case "sphere":
      g = new THREE.SphereGeometry(0.5, 12, 8);
      break;
    case "pod":
      g = new THREE.SphereGeometry(0.5, 40, 24);
      break;
    case "tube":
      g = new THREE.CylinderGeometry(0.5, 0.5, 1, 28, 1);
      break;
    case "torus":
      // lies flat (in the XZ plane); tilt it to stand it up
      g = new THREE.TorusGeometry(0.5, 0.035, 8, 56);
      g.rotateX(Math.PI / 2);
      break;
    case "cone":
      g = new THREE.ConeGeometry(0.5, 1, 12);
      break;
    case "arch":
      g = archGeometry();
      break;
    case "pane":
    case "box":
    default:
      g = new THREE.BoxGeometry(1, 1, 1);
  }
  cache.set(kind, g);
  return g;
}
