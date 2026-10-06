import { forwardRef, useContext, useEffect, useImperativeHandle, useMemo, useRef, useState, type ReactNode } from "react";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { OrbitControls, OrthographicCamera, PerspectiveCamera } from "@react-three/drei";
import { Bloom, EffectComposer, EffectComposerContext, N8AO } from "@react-three/postprocessing";
import * as THREE from "three";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import type { RenderMode, Scene } from "../lib/types";
import type { ReplayModel } from "../lib/replay";
import { SceneMeshes } from "./SceneMeshes";
import { ReplayVoxels } from "./ReplayVoxels";
import { Clouds } from "./Clouds";
import { EdgeEffect } from "./EdgeEffect";

export type Quality = "low" | "medium" | "high";
export type Projection = "perspective" | "orthographic";

export interface ViewerApi {
  resetCamera(): void;
  screenshot(): Promise<Blob | null>;
  exportRoot(): THREE.Object3D | null;
  canvas(): HTMLCanvasElement | null;
}

export interface ViewerProps {
  scene: Scene | null;
  mode?: RenderMode;
  quality?: Quality;
  projection?: Projection;
  selected?: number | null;
  onSelect?: (c: number | null) => void;
  replay?: { model: ReplayModel; frame: number } | null;
  transparent?: boolean;
  autoRotate?: boolean;
  cinematic?: boolean;
  interactive?: boolean;
  reducedMotion?: boolean;
  clouds?: boolean;
  postprocessing?: boolean;
  className?: string;
  children?: ReactNode;
  label?: string;
  /** Multiplies the fitted camera distance (>1 pulls back). */
  distanceScale?: number;
  /** Shifts the look-at target vertically, in units of the design height. */
  targetLift?: number;
}

/** Diorama skies per world: colour of zenith, horizon and fog, plus the sun. Purely presentation. */
export const SKIES: Record<string, { top: string; horizon: string; fog: string; sun: string; sunEl: number; sunAz: number; sunI: number; hemiSky: string; hemiGround: string; hemiI: number; exposure: number }> = {
  dusk: { top: "#13223f", horizon: "#f0b08f", fog: "#e9b49a", sun: "#ffd2a1", sunEl: 24, sunAz: -58, sunI: 3.1, hemiSky: "#a9c6ee", hemiGround: "#6f5845", hemiI: 1.05, exposure: 1.05 },
  morning: { top: "#3e78b8", horizon: "#f5dcc2", fog: "#eedccb", sun: "#fff0d8", sunEl: 34, sunAz: -40, sunI: 3.2, hemiSky: "#bcd7f4", hemiGround: "#8a7660", hemiI: 1.2, exposure: 1.0 },
  garden: { top: "#4f93cf", horizon: "#e6efe8", fog: "#dfe9e2", sun: "#fff6e6", sunEl: 52, sunAz: -30, sunI: 3.0, hemiSky: "#c9e2f5", hemiGround: "#6d7a55", hemiI: 1.25, exposure: 1.0 },
  night: { top: "#060c1a", horizon: "#22345a", fog: "#1d2c4c", sun: "#9fb6ff", sunEl: 38, sunAz: 40, sunI: 0.9, hemiSky: "#3c5288", hemiGround: "#1a1a24", hemiI: 0.75, exposure: 1.2 },
  golden: { top: "#35588a", horizon: "#ffcf96", fog: "#f2c79a", sun: "#ffc27a", sunEl: 16, sunAz: -70, sunI: 3.4, hemiSky: "#b2c8e8", hemiGround: "#7a5a3c", hemiI: 1.0, exposure: 1.05 },
  mist: { top: "#6d8aa3", horizon: "#e2e9e5", fog: "#d8e2de", sun: "#fffaf0", sunEl: 44, sunAz: -50, sunI: 2.4, hemiSky: "#dbe7ee", hemiGround: "#7d8a7a", hemiI: 1.35, exposure: 1.0 },
  paper: { top: "#d9cfbd", horizon: "#efe8da", fog: "#ebe3d4", sun: "#fff7ea", sunEl: 46, sunAz: -45, sunI: 2.6, hemiSky: "#f3ecdf", hemiGround: "#a69782", hemiI: 1.3, exposure: 1.0 },
};

/** True when the backdrop behind the top of the viewport is light, so overlaid text must be dark. */
export function backdropIsLight(scene: Scene | null, mode: RenderMode): boolean {
  if (mode === "clay" || mode === "ink") return true;
  if (mode === "blueprint") return false;
  const hex = skyOf(scene).top.slice(1);
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4;
}

function skyOf(scene: Scene | null) {
  return SKIES[scene?.wire.atmosphere.sky ?? "dusk"] ?? SKIES.dusk;
}

const BACKGROUND: Record<RenderMode, { top: string; horizon: string; fog: string }> = {
  diorama: { top: "#13223f", horizon: "#f0b08f", fog: "#e9b49a" },
  clay: { top: "#cfc9bf", horizon: "#e4dfd6", fog: "#e4dfd6" },
  ink: { top: "#f6f1e6", horizon: "#f6f1e6", fog: "#f6f1e6" },
  blueprint: { top: "#0f2b4b", horizon: "#16395f", fog: "#16395f" },
};

function gradientTexture(top: string, horizon: string) {
  const c = document.createElement("canvas");
  c.width = 4;
  c.height = 256;
  const g = c.getContext("2d")!;
  const grd = g.createLinearGradient(0, 0, 0, 256);
  grd.addColorStop(0, top);
  grd.addColorStop(0.62, horizon);
  grd.addColorStop(1, horizon);
  g.fillStyle = grd;
  g.fillRect(0, 0, 4, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function frameFor(scene: Scene | null) {
  if (!scene) return { target: new THREE.Vector3(0, 20, 0), distance: 110, az: -32, el: 24, extent: 80 };
  const b = scene.wire.bounds;
  const dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
  const ex = Math.max(dx, dz, dy * 1.25);
  const radius = 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz);
  const cy = b[1] + dy * 0.46;
  const cam = scene.wire.camera;
  const distance = (radius / Math.sin(THREE.MathUtils.degToRad(17))) * 0.82;
  return { target: new THREE.Vector3((b[0] + b[3]) / 2, cy, (b[2] + b[5]) / 2), distance, az: cam.azimuth, el: cam.elevation, extent: ex };
}

function placeCamera(camera: THREE.Camera, f: ReturnType<typeof frameFor>, controls: OrbitControlsImpl | null) {
  const az = THREE.MathUtils.degToRad(f.az), el = THREE.MathUtils.degToRad(f.el);
  const dir = new THREE.Vector3(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el));
  camera.position.copy(f.target).addScaledVector(dir, f.distance);
  camera.lookAt(f.target);
  if ((camera as THREE.OrthographicCamera).isOrthographicCamera) {
    const oc = camera as THREE.OrthographicCamera;
    oc.zoom = 1;
    oc.updateProjectionMatrix();
  }
  if (controls) {
    controls.target.copy(f.target);
    controls.update();
  }
}

/** Fits the orthographic frustum to the design (zoom handled by OrbitControls). */
function OrthoFit({ extent }: { extent: number }) {
  const { camera, size } = useThree();
  useEffect(() => {
    const oc = camera as THREE.OrthographicCamera;
    if (!oc.isOrthographicCamera) return;
    const aspect = size.width / Math.max(1, size.height);
    const h = extent * 1.18;
    oc.left = (-h * aspect) / 2;
    oc.right = (h * aspect) / 2;
    oc.top = h / 2;
    oc.bottom = -h / 2;
    oc.near = -500;
    oc.far = 2000;
    oc.updateProjectionMatrix();
  }, [camera, size, extent]);
  return null;
}

function Lights({ mode, extent, target, sky }: { mode: RenderMode; extent: number; target: THREE.Vector3; sky: (typeof SKIES)[string] }) {
  const sun = useRef<THREE.DirectionalLight>(null);
  const { scene } = useThree();
  useEffect(() => {
    const l = sun.current;
    if (!l) return;
    const half = extent * 0.62;
    const cam = l.shadow.camera as THREE.OrthographicCamera;
    cam.left = -half;
    cam.right = half;
    cam.top = half;
    cam.bottom = -half;
    cam.near = 1;
    cam.far = extent * 4;
    cam.updateProjectionMatrix();
    l.target.position.copy(target);
    scene.add(l.target);
    return () => {
      scene.remove(l.target);
    };
  }, [extent, target, scene]);
  const sunPos = useMemo(() => {
    const az = THREE.MathUtils.degToRad(mode === "diorama" ? sky.sunAz : -58), el = THREE.MathUtils.degToRad(mode === "diorama" ? sky.sunEl : 48);
    return new THREE.Vector3(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)).multiplyScalar(extent * 1.6).add(target);
  }, [mode, extent, target, sky]);
  if (mode === "ink" || mode === "blueprint") {
    return (
      <>
        <ambientLight intensity={mode === "ink" ? 2.1 : 1.6} />
        <directionalLight position={sunPos} intensity={mode === "ink" ? 0.9 : 0.8} />
      </>
    );
  }
  const warm = mode === "diorama";
  return (
    <>
      <hemisphereLight args={[warm ? sky.hemiSky : "#ffffff", warm ? sky.hemiGround : "#b9b2a6", warm ? sky.hemiI : 1.2]} />
      <ambientLight intensity={warm ? 0.12 : 0.35} />
      <directionalLight
        ref={sun}
        position={sunPos}
        intensity={warm ? sky.sunI : 2.2}
        color={warm ? sky.sun : "#ffffff"}
        castShadow
        shadow-bias={-0.00035}
        shadow-normalBias={0.04}
      />
    </>
  );
}

function Background({ mode, transparent, distance, sky }: { mode: RenderMode; transparent: boolean; distance: number; sky: (typeof SKIES)[string] }) {
  const { scene, gl } = useThree();
  useEffect(() => {
    const bg = mode === "diorama" ? sky : BACKGROUND[mode];
    gl.toneMappingExposure = mode === "diorama" ? sky.exposure : 1.0;
    const tex = transparent ? null : gradientTexture(bg.top, bg.horizon);
    scene.background = tex;
    scene.fog = mode === "diorama" || mode === "clay" ? new THREE.Fog(bg.fog, distance * 1.05, distance * 3.4) : null;
    return () => {
      tex?.dispose();
      scene.background = null;
      scene.fog = null;
    };
  }, [mode, transparent, scene, distance, sky, gl]);
  return null;
}

/** Slow cinematic orbit with gentle elevation and dolly changes (disabled for reduced motion). */
function Cinematic({ enabled, target, distance }: { enabled: boolean; target: THREE.Vector3; distance: number }) {
  const { camera, controls } = useThree();
  const t0 = useRef<number | null>(null);
  useFrame(({ clock }) => {
    if (!enabled) {
      t0.current = null;
      return;
    }
    if (t0.current === null) t0.current = clock.elapsedTime;
    const t = clock.elapsedTime - t0.current;
    const az = -0.6 + t * 0.09;
    const el = 0.32 + Math.sin(t * 0.23) * 0.16;
    const d = distance * (0.82 + Math.sin(t * 0.17) * 0.22);
    camera.position.set(target.x + Math.sin(az) * Math.cos(el) * d, target.y + Math.sin(el) * d, target.z + Math.cos(az) * Math.cos(el) * d);
    camera.lookAt(target);
    const c = controls as unknown as OrbitControlsImpl | null;
    if (c) c.target.copy(target);
  });
  return null;
}

function EdgePass({ mode }: { mode: RenderMode }) {
  const ctx = useContext(EffectComposerContext) as unknown as { normalPass: { texture: THREE.Texture } | null };
  const effect = useMemo(
    () =>
      new EdgeEffect({
        normalBuffer: ctx.normalPass?.texture ?? null,
        lineColor: mode === "ink" ? "#1d1a17" : "#e8f3ff",
        paperColor: mode === "ink" ? "#f6f1e6" : "#123760",
        thickness: 1.0,
        faceMix: mode === "ink" ? 0.55 : 0.75,
      }),
    [ctx.normalPass, mode],
  );
  useEffect(() => () => effect.dispose(), [effect]);
  return <primitive object={effect} />;
}

function Post({ mode, quality }: { mode: RenderMode; quality: Quality }) {
  if (quality === "low" && (mode === "diorama" || mode === "clay")) return null;
  if (mode === "ink" || mode === "blueprint") {
    return (
      <EffectComposer enableNormalPass multisampling={quality === "high" ? 4 : 0}>
        <EdgePass mode={mode} />
      </EffectComposer>
    );
  }
  return (
    <EffectComposer multisampling={quality === "high" ? 4 : 0}>
      <N8AO aoRadius={2.2} intensity={mode === "clay" ? 3.2 : 2.2} distanceFalloff={1.2} quality={quality === "high" ? "high" : "medium"} halfRes={quality !== "high"} />
      {mode === "diorama" ? <Bloom intensity={0.55} luminanceThreshold={0.92} luminanceSmoothing={0.12} mipmapBlur radius={0.55} /> : <></>}
    </EffectComposer>
  );
}

function Capture({ apiRef }: { apiRef: React.MutableRefObject<{ gl: THREE.WebGLRenderer | null; root: THREE.Object3D | null; reset: () => void }> }) {
  const { gl } = useThree();
  useEffect(() => {
    apiRef.current.gl = gl;
  }, [gl, apiRef]);
  return null;
}

export const Viewer = forwardRef<ViewerApi, ViewerProps>(function Viewer(
  {
    scene, mode = "diorama", quality = "medium", projection = "perspective", selected = null, onSelect, replay = null,
    transparent = false, autoRotate = false, cinematic = false, interactive = true, reducedMotion = false, clouds = true,
    postprocessing = true, className, children, label, distanceScale = 1, targetLift = 0,
  },
  ref,
) {
  const f = useMemo(() => {
    const base = frameFor(scene);
    const h = scene ? scene.wire.bounds[4] - scene.wire.bounds[1] : 40;
    return { ...base, distance: base.distance * distanceScale, target: base.target.clone().add(new THREE.Vector3(0, h * targetLift, 0)) };
  }, [scene, distanceScale, targetLift]);
  const controls = useRef<OrbitControlsImpl>(null);
  const cameraRef = useRef<THREE.Camera>(null);
  const designRoot = useRef<THREE.Group>(null);
  const inner = useRef<{ gl: THREE.WebGLRenderer | null; root: THREE.Object3D | null; reset: () => void }>({ gl: null, root: null, reset: () => {} });
  const [readyKey, setReadyKey] = useState(0);

  // re-frame when a different design arrives (not on every re-render)
  const sceneKey = scene ? `${scene.wire.config.preset}:${scene.wire.config.seed}:${scene.wire.jobId ?? ""}` : "none";
  const lastFramedPreset = useRef<string>("");
  useEffect(() => {
    const presetKey = scene ? scene.wire.config.preset : "none";
    if (cameraRef.current && presetKey !== lastFramedPreset.current) {
      placeCamera(cameraRef.current, f, controls.current);
      lastFramedPreset.current = presetKey;
    }
  }, [sceneKey, f, readyKey, scene]);

  useImperativeHandle(ref, () => ({
    resetCamera() {
      if (cameraRef.current) placeCamera(cameraRef.current, f, controls.current);
    },
    async screenshot() {
      const gl = inner.current.gl;
      if (!gl) return null;
      return await new Promise<Blob | null>((res) => gl.domElement.toBlob((b) => res(b), "image/png"));
    },
    exportRoot() {
      return designRoot.current;
    },
    canvas() {
      return inner.current.gl?.domElement ?? null;
    },
  }), [f]);

  const showClouds = clouds && mode === "diorama" && !!scene?.wire.atmosphere.cloudy && !replay;
  const sky = skyOf(scene);
  const dpr: [number, number] = quality === "high" ? [1, 2] : quality === "medium" ? [1, 1.5] : [1, 1];
  const shadowSize = quality === "high" ? 4096 : quality === "medium" ? 2048 : 1024;

  return (
    <div className={className} aria-label={label ?? "3D architectural viewer"} role="img">
      <Canvas
        shadows={{ type: THREE.PCFShadowMap }}
        dpr={dpr}
        gl={{ antialias: quality !== "low", alpha: transparent, preserveDrawingBuffer: true, powerPreference: "high-performance" }}
        onCreated={({ gl }) => {
          gl.toneMapping = THREE.ACESFilmicToneMapping;
          gl.toneMappingExposure = mode === "diorama" ? 1.05 : 1.0;
          gl.shadowMap.autoUpdate = true;
          setReadyKey((k) => k + 1);
        }}
      >
        <Capture apiRef={inner} />
        {projection === "perspective" ? (
          <PerspectiveCamera makeDefault ref={cameraRef as React.Ref<THREE.PerspectiveCamera>} fov={34} near={0.5} far={4000} position={[f.distance, f.distance * 0.5, f.distance]} />
        ) : (
          <OrthographicCamera makeDefault ref={cameraRef as React.Ref<THREE.OrthographicCamera>} position={[f.distance, f.distance * 0.7, f.distance]} />
        )}
        {projection === "orthographic" && <OrthoFit extent={f.extent} />}
        <OrbitControls
          ref={controls as React.Ref<OrbitControlsImpl>}
          makeDefault
          enabled={interactive && !cinematic}
          enableDamping
          dampingFactor={0.08}
          maxPolarAngle={Math.PI * 0.495}
          minDistance={8}
          maxDistance={f.distance * 3}
          autoRotate={autoRotate && !reducedMotion && !cinematic}
          autoRotateSpeed={0.35}
          target={f.target}
        />
        <Cinematic enabled={cinematic && !reducedMotion} target={f.target} distance={f.distance} />
        <Background mode={mode} transparent={transparent} distance={f.distance} sky={sky} />
        <Lights mode={mode} extent={f.extent} target={f.target} sky={sky} />
        <group ref={designRoot}>
          {replay ? (
            <ReplayVoxels model={replay.model} frame={replay.frame} />
          ) : scene ? (
            <SceneMeshes scene={scene} mode={mode} selected={selected} onSelect={onSelect} shadows={quality !== "low"} />
          ) : null}
        </group>
        {showClouds && scene && (
          <Clouds level={scene.wire.atmosphere.cloudLevel} innerRadius={Math.max(30, f.extent * 0.42)} seed={scene.wire.config.seed} animate={!reducedMotion} count={quality === "low" ? 50 : 90} />
        )}
        <ShadowMapSize size={shadowSize} />
        {postprocessing && !transparent && <Post mode={mode} quality={quality} />}
        {children}
      </Canvas>
    </div>
  );
});

function ShadowMapSize({ size }: { size: number }) {
  const { scene } = useThree();
  useEffect(() => {
    scene.traverse((o) => {
      const l = o as THREE.DirectionalLight;
      if (l.isDirectionalLight && l.castShadow && l.shadow.mapSize.x !== size) {
        l.shadow.mapSize.set(size, size);
        l.shadow.map?.dispose();
        l.shadow.map = null as unknown as THREE.WebGLRenderTarget;
      }
    });
  }, [scene, size]);
  return null;
}

export function webglAvailable(): boolean {
  try {
    const c = document.createElement("canvas");
    return !!(c.getContext("webgl2") || c.getContext("webgl"));
  } catch {
    return false;
  }
}
