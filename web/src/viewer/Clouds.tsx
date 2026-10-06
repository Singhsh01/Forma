import { useEffect, useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";

/** Soft puff texture drawn once on a canvas (no image assets). */
function cloudTexture(): THREE.Texture {
  const s = 128;
  const c = document.createElement("canvas");
  c.width = c.height = s;
  const g = c.getContext("2d")!;
  const puffs = [
    [0.5, 0.58, 0.34], [0.32, 0.62, 0.24], [0.68, 0.62, 0.25], [0.42, 0.45, 0.22], [0.6, 0.46, 0.2], [0.22, 0.68, 0.16], [0.8, 0.68, 0.15],
  ];
  for (const [x, y, r] of puffs) {
    const grd = g.createRadialGradient(x * s, y * s, 0, x * s, y * s, r * s);
    grd.addColorStop(0, "rgba(255,255,255,0.95)");
    grd.addColorStop(0.55, "rgba(255,255,255,0.55)");
    grd.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = grd;
    g.fillRect(0, 0, s, s);
  }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function mulberry(seed: number) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Props {
  level: number;
  innerRadius: number;
  seed: number;
  tint?: string;
  animate?: boolean;
  count?: number;
}

/** A lightweight cloud sea: camera-facing instanced puffs around the site at cloud height. */
export function Clouds({ level, innerRadius, seed, tint = "#fff3ea", animate = true, count = 90 }: Props) {
  const ref = useRef<THREE.InstancedMesh>(null);
  const tex = useMemo(() => cloudTexture(), []);
  const mat = useMemo(
    () => new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false, color: tint, opacity: 0.92, fog: true }),
    [tex, tint],
  );
  const geo = useMemo(() => new THREE.PlaneGeometry(1, 0.62), []);
  const items = useMemo(() => {
    const r = mulberry(seed * 7919 + 13);
    return Array.from({ length: count }, (_, i) => {
      const ring = i < count * 0.78;
      const ang = r() * Math.PI * 2;
      const rad = ring ? innerRadius * (0.55 + r() * 0.9) + r() * 40 : innerRadius * 0.3 + r() * 90;
      return {
        x: Math.cos(ang) * rad,
        z: Math.sin(ang) * rad,
        y: ring ? level - 2 + r() * 5 : level - 8 + r() * 4,
        s: ring ? 12 + r() * 20 : 20 + r() * 26,
        drift: 0.15 + r() * 0.35,
        phase: r() * Math.PI * 2,
      };
    });
  }, [seed, count, innerRadius, level]);
  const tmp = useMemo(() => ({ m: new THREE.Matrix4(), p: new THREE.Vector3(), s: new THREE.Vector3(), q: new THREE.Quaternion() }), []);

  useFrame(({ camera, clock }) => {
    const mesh = ref.current;
    if (!mesh) return;
    const t = animate ? clock.elapsedTime : 0;
    tmp.q.copy(camera.quaternion);
    items.forEach((c, i) => {
      tmp.p.set(c.x + Math.sin(t * 0.05 * c.drift + c.phase) * 3, c.y + Math.sin(t * 0.12 + c.phase) * 0.4, c.z + Math.cos(t * 0.04 * c.drift + c.phase) * 3);
      tmp.s.set(c.s, c.s, c.s);
      tmp.m.compose(tmp.p, tmp.q, tmp.s);
      mesh.setMatrixAt(i, tmp.m);
    });
    mesh.instanceMatrix.needsUpdate = true;
  });

  useEffect(() => () => {
    tex.dispose();
    mat.dispose();
    geo.dispose();
  }, [tex, mat, geo]);

  return <instancedMesh ref={ref} args={[geo, mat, items.length]} frustumCulled={false} renderOrder={2} />;
}
