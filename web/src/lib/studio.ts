import { useSyncExternalStore } from "react";
import { api, ApiError, followJob } from "./api";
import { ReplayModel } from "./replay";
import { loadStaticConfig, loadStaticInspector, loadStaticRefinement, loadStaticReplay, loadStaticScene } from "./staticScenes";
import type {
  GenerationConfig, InspectorWire, JobStatus, PresetInfo, RefineSettings, RefinementWire, RenderMode, Scene,
} from "./types";
import type { Projection, Quality } from "../viewer/Viewer";

export type Tab = "params" | "rules" | "checks" | "refine" | "export";

export interface Design {
  jobId: string | null;
  source: "live" | "static";
  config: GenerationConfig;
  scene: Scene;
  refinement: RefinementWire | null;
  refineSettings: RefineSettings | null;
}

export interface StudioState {
  presets: PresetInfo[];
  presetsStatus: "loading" | "ready" | "offline";
  engineVersion: string | null;
  presetId: string;
  paramsByPreset: Record<string, Record<string, number>>;
  seed: number;
  overridesByPreset: Record<string, Record<string, string>>;
  job: JobStatus | null;
  jobKind: "generate" | "refine" | null;
  current: Design | null;
  previous: Design | null;
  loadingInitial: boolean;
  error: string | null;
  notice: string | null;
  tab: Tab;
  mode: RenderMode;
  projection: Projection;
  quality: Quality;
  cinematic: boolean;
  clouds: boolean;
  compare: boolean;
  compareShowBefore: boolean;
  selected: number | null;
  inspector: { status: "idle" | "loading" | "ready" | "error"; key: string | null; data: InspectorWire | null; error?: string };
  replay: { status: "idle" | "loading" | "ready" | "error"; key: string | null; model: ReplayModel | null; frame: number; playing: boolean; active: boolean; error?: string };
  refine: RefineSettings;
}

export const DEFAULT_REFINE: RefineSettings = {
  iterations: 2000,
  temperature: 0.25,
  temperatureEnd: 0.02,
  mode: "mcmc",
  weights: {},
  mcmcSeed: 1,
  keep: "best",
};

type Listener = () => void;

/**
 * Studio state container. Async actions guard against stale responses with a token: when the
 * user starts a newer job, results of the older one are ignored. A failed or cancelled job never
 * replaces the last successful design.
 */
/** Each world states the projection it was composed for (the Escher labyrinth is isometric). */
function preferredProjection(scene: Scene): Projection {
  return scene.wire.atmosphere.projection === "orthographic" ? "orthographic" : "perspective";
}

export class StudioStore {
  private state: StudioState;
  private listeners = new Set<Listener>();
  private token = 0;
  private stopFollow: (() => void) | null = null;
  private playTimer: ReturnType<typeof setInterval> | null = null;

  constructor(initial?: Partial<StudioState>) {
    this.state = {
      presets: [],
      presetsStatus: "loading",
      engineVersion: null,
      presetId: "library",
      paramsByPreset: {},
      seed: 7,
      overridesByPreset: {},
      job: null,
      jobKind: null,
      current: null,
      previous: null,
      loadingInitial: true,
      error: null,
      notice: null,
      tab: "params",
      mode: "diorama",
      projection: "perspective",
      quality: "medium",
      cinematic: false,
      clouds: true,
      compare: false,
      compareShowBefore: false,
      selected: null,
      inspector: { status: "idle", key: null, data: null },
      replay: { status: "idle", key: null, model: null, frame: -1, playing: false, active: false },
      refine: { ...DEFAULT_REFINE },
      ...initial,
    };
  }

  get = () => this.state;

  subscribe = (l: Listener) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };

  private set(patch: Partial<StudioState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }

  // ------------------------------------------------------------ derived

  preset(): PresetInfo | undefined {
    return this.state.presets.find((p) => p.id === this.state.presetId);
  }

  params(): Record<string, number> {
    const p = this.preset();
    const own = this.state.paramsByPreset[this.state.presetId] ?? {};
    const out: Record<string, number> = {};
    p?.params.forEach((s) => (out[s.key] = own[s.key] ?? s.default));
    return out;
  }

  pendingConfig(): GenerationConfig {
    return {
      preset: this.state.presetId,
      seed: this.state.seed,
      params: this.params(),
      ruleOverrides: { ...(this.state.overridesByPreset[this.state.presetId] ?? {}) },
    };
  }

  /** True when the controls describe a design different from the one on screen. */
  isDirty(): boolean {
    const cur = this.state.current;
    if (!cur) return true;
    const a = this.pendingConfig(), b = cur.config;
    if (a.preset !== b.preset || a.seed !== b.seed) return true;
    for (const k of Object.keys(a.params)) if (Math.abs((a.params[k] ?? 0) - (b.params[k] ?? NaN)) > 1e-9) return true;
    const ao = a.ruleOverrides, bo = b.ruleOverrides ?? {};
    const keys = new Set([...Object.keys(ao), ...Object.keys(bo)]);
    for (const k of keys) if ((ao[k] ?? "") !== (bo[k] ?? "")) return true;
    return false;
  }

  // ------------------------------------------------------------ setup

  async init(presetFromUrl?: string | null, seedFromUrl?: number | null) {
    const preset = presetFromUrl ?? "library";
    this.set({ presetId: preset, loadingInitial: true });
    // presets come from the engine; the precomputed scene shows something instantly either way
    const presetsP = api.presets().then(
      (r) => this.set({ presets: r.presets, presetsStatus: "ready", engineVersion: r.generatorVersion }),
      () => this.set({ presetsStatus: "offline", notice: "The Java engine is not reachable. Showing precomputed designs; start the server to generate new ones." }),
    );
    try {
      const [scene, config, refinement] = await Promise.all([loadStaticScene(preset), loadStaticConfig(preset), loadStaticRefinement(preset)]);
      this.set({
        current: { jobId: null, source: "static", config, scene, refinement: null, refineSettings: null },
          projection: preferredProjection(scene),
        seed: seedFromUrl ?? config.seed,
        paramsByPreset: { ...this.state.paramsByPreset, [preset]: { ...config.params } },
        loadingInitial: false,
      });
      void refinement;
    } catch {
      this.set({ loadingInitial: false });
    }
    await presetsP;
    if (seedFromUrl != null && this.state.presetsStatus === "ready" && this.state.current?.config.seed !== seedFromUrl) {
      void this.generate();
    }
  }

  // ------------------------------------------------------------ controls

  selectPreset(id: string) {
    if (id === this.state.presetId) return;
    this.set({ presetId: id, selected: null, error: null });
    // show the precomputed design for that world straight away, then the user can generate
    const t = ++this.token;
    this.cancelFollow();
    Promise.all([loadStaticScene(id), loadStaticConfig(id)]).then(
      ([scene, config]) => {
        if (t !== this.token) return;
        this.set({
          current: { jobId: null, source: "static", config, scene, refinement: null, refineSettings: null },
          projection: preferredProjection(scene),
          previous: null,
          compare: false,
          seed: config.seed,
          paramsByPreset: { ...this.state.paramsByPreset, [id]: { ...config.params, ...(this.state.paramsByPreset[id] ?? {}) } },
          job: null,
          jobKind: null,
        });
        this.resetDerived();
      },
      () => {
        if (t !== this.token) return;
        void this.generate();
      },
    );
  }

  setParam(key: string, value: number) {
    const id = this.state.presetId;
    this.set({ paramsByPreset: { ...this.state.paramsByPreset, [id]: { ...(this.state.paramsByPreset[id] ?? {}), [key]: value } } });
  }

  resetParams() {
    const id = this.state.presetId;
    const next = { ...this.state.paramsByPreset };
    delete next[id];
    this.set({ paramsByPreset: next });
  }

  setSeed(seed: number) {
    if (!Number.isFinite(seed)) return;
    this.set({ seed: Math.max(0, Math.min(2 ** 31 - 1, Math.trunc(seed))) });
  }

  randomizeSeed() {
    this.setSeed(Math.floor(Math.random() * 999_999) + 1);
  }

  setOverride(program: string, source: string | null) {
    const id = this.state.presetId;
    const cur = { ...(this.state.overridesByPreset[id] ?? {}) };
    if (source === null) delete cur[program];
    else cur[program] = source;
    this.set({ overridesByPreset: { ...this.state.overridesByPreset, [id]: cur } });
  }

  setTab(tab: Tab) {
    this.set({ tab });
    if (tab === "rules" || tab === "checks") void this.ensureInspector();
  }

  setView(patch: Partial<Pick<StudioState, "mode" | "projection" | "quality" | "cinematic" | "clouds" | "compareShowBefore">>) {
    this.set(patch);
  }

  select(c: number | null) {
    this.set({ selected: c });
  }

  dismissError() {
    this.set({ error: null });
  }

  dismissNotice() {
    this.set({ notice: null });
  }

  setRefine(patch: Partial<RefineSettings>) {
    this.set({ refine: { ...this.state.refine, ...patch } });
  }

  setRefineWeight(key: string, v: number) {
    this.set({ refine: { ...this.state.refine, weights: { ...this.state.refine.weights, [key]: v } } });
  }

  toggleCompare(on?: boolean) {
    const want = on ?? !this.state.compare;
    if (want && !this.state.previous) return;
    this.set({ compare: want, compareShowBefore: false });
  }

  // ------------------------------------------------------------ jobs

  private cancelFollow() {
    this.stopFollow?.();
    this.stopFollow = null;
  }

  private resetDerived() {
    this.stopPlayback();
    this.set({
      inspector: { status: "idle", key: null, data: null },
      replay: { status: "idle", key: null, model: null, frame: -1, playing: false, active: false },
    });
    if (this.state.tab === "rules" || this.state.tab === "checks") void this.ensureInspector();
  }

  async generate(configOverride?: GenerationConfig) {
    const config = configOverride ?? this.pendingConfig();
    const t = ++this.token;
    this.cancelFollow();
    this.set({ error: null, jobKind: "generate", job: { jobId: "", type: "generate", status: "queued", stage: "queued", fraction: 0, message: "Sending to the engine", preset: config.preset, seed: config.seed, createdAt: Date.now() } });
    try {
      const job = await api.generate(config);
      if (t !== this.token) return;
      this.set({ job });
      const f = followJob(job.jobId, (s) => {
        if (t === this.token) this.set({ job: s });
      });
      this.stopFollow = f.stop;
      const end = await f.done;
      if (t !== this.token) return;
      if (end.status !== "completed") {
        this.set({ job: null, jobKind: null, error: end.status === "cancelled" ? null : `Generation failed: ${end.error ?? end.message}. The previous design is still shown.`, notice: end.status === "cancelled" ? "Generation cancelled. The previous design is still shown." : this.state.notice });
        return;
      }
      const scene = await api.scene(end.jobId);
      if (t !== this.token) return;
      const prev = this.state.current;
      this.set({
        current: { jobId: end.jobId, source: "live", config, scene, refinement: null, refineSettings: null },
        previous: prev && prev.config.preset === config.preset ? prev : null,
        compare: false,
        job: null,
        jobKind: null,
        selected: null,
        presetId: config.preset,
      });
      this.resetDerived();
    } catch (e) {
      if (t !== this.token) return;
      this.set({ job: null, jobKind: null, error: messageOf(e) + " The previous design is still shown." });
    }
  }

  async refine() {
    const cur = this.state.current;
    if (!cur) return;
    const settings = { ...this.state.refine, weights: { ...this.state.refine.weights } };
    const t = ++this.token;
    this.cancelFollow();
    this.set({ error: null, jobKind: "refine", job: { jobId: "", type: "refine", status: "queued", stage: "queued", fraction: 0, message: "Sending to the sampler", preset: cur.config.preset, seed: cur.config.seed, createdAt: Date.now() } });
    try {
      const job = await api.refine(cur.jobId, cur.config, settings);
      if (t !== this.token) return;
      this.set({ job });
      const f = followJob(job.jobId, (s) => {
        if (t === this.token) this.set({ job: s });
      });
      this.stopFollow = f.stop;
      const end = await f.done;
      if (t !== this.token) return;
      if (end.status !== "completed") {
        this.set({ job: null, jobKind: null, error: end.status === "cancelled" ? null : `Refinement failed: ${end.error ?? end.message}`, notice: end.status === "cancelled" ? "Refinement cancelled. The design is unchanged." : this.state.notice });
        return;
      }
      const [scene, refinement] = await Promise.all([api.scene(end.jobId), api.refinement(end.jobId)]);
      if (t !== this.token) return;
      this.set({
        previous: cur,
        current: { jobId: end.jobId, source: "live", config: cur.config, scene, refinement, refineSettings: settings },
        compare: true,
        compareShowBefore: false,
        job: null,
        jobKind: null,
      });
      this.resetDerived();
    } catch (e) {
      if (t !== this.token) return;
      this.set({ job: null, jobKind: null, error: messageOf(e) });
    }
  }

  async cancel() {
    const job = this.state.job;
    if (!job) return;
    if (!job.jobId) {
      ++this.token;
      this.set({ job: null, jobKind: null, notice: "Cancelled before the engine started." });
      return;
    }
    // cancelling means "I don't want this result": release the studio at once, keep the current
    // design, and tell the engine to stop (it checks cancellation between and inside stages)
    ++this.token;
    this.cancelFollow();
    this.set({ job: null, jobKind: null, notice: "Cancelled. The previous design is still shown." });
    try {
      await api.cancel(job.jobId);
    } catch (e) {
      this.set({ error: messageOf(e) });
    }
  }

  // ------------------------------------------------------------ inspector & replay

  private designKey(d: Design | null) {
    return d ? `${d.source}:${d.jobId ?? d.config.preset}` : null;
  }

  async ensureInspector() {
    const d = this.state.current;
    const key = this.designKey(d);
    if (!d || (this.state.inspector.key === key && this.state.inspector.status !== "error")) return;
    this.set({ inspector: { status: "loading", key, data: null } });
    try {
      const data = d.jobId ? await api.inspector(d.jobId) : await loadStaticInspector(d.config.preset);
      if (this.designKey(this.state.current) !== key) return;
      this.set({ inspector: { status: "ready", key, data } });
    } catch (e) {
      if (this.designKey(this.state.current) !== key) return;
      this.set({ inspector: { status: "error", key, data: null, error: messageOf(e) } });
    }
  }

  async openReplay() {
    const d = this.state.current;
    const key = this.designKey(d);
    if (!d) return;
    if (this.state.replay.key === key && this.state.replay.model) {
      this.set({ replay: { ...this.state.replay, active: true } });
      return;
    }
    this.set({ replay: { status: "loading", key, model: null, frame: -1, playing: false, active: true } });
    try {
      const wire = d.jobId ? await api.replay(d.jobId) : await loadStaticReplay(d.config.preset);
      if (this.designKey(this.state.current) !== key) return;
      const model = new ReplayModel(wire);
      this.set({ replay: { status: "ready", key, model, frame: 0, playing: false, active: true } });
      this.play();
    } catch (e) {
      if (this.designKey(this.state.current) !== key) return;
      this.set({ replay: { status: "error", key, model: null, frame: -1, playing: false, active: false, error: messageOf(e) } });
    }
  }

  closeReplay() {
    this.stopPlayback();
    this.set({ replay: { ...this.state.replay, active: false, playing: false } });
  }

  setFrame(frame: number) {
    const m = this.state.replay.model;
    if (!m) return;
    this.set({ replay: { ...this.state.replay, frame: Math.max(0, Math.min(m.frameCount - 1, Math.round(frame))) } });
  }

  step(delta: number) {
    this.pause();
    this.setFrame(this.state.replay.frame + delta);
  }

  jumpToStage(stageIndex: number) {
    const m = this.state.replay.model;
    if (!m) return;
    this.pause();
    this.setFrame(m.stages[stageIndex]?.lastFrame ?? 0);
  }

  play() {
    const m = this.state.replay.model;
    if (!m) return;
    this.stopPlayback();
    let frame = this.state.replay.frame >= m.frameCount - 1 ? 0 : this.state.replay.frame;
    this.set({ replay: { ...this.state.replay, playing: true, frame } });
    this.playTimer = setInterval(() => {
      frame += 1;
      if (frame >= m.frameCount) {
        this.stopPlayback();
        this.set({ replay: { ...this.state.replay, playing: false, frame: m.frameCount - 1 } });
        return;
      }
      this.set({ replay: { ...this.state.replay, frame } });
    }, 70);
  }

  pause() {
    this.stopPlayback();
    if (this.state.replay.playing) this.set({ replay: { ...this.state.replay, playing: false } });
  }

  private stopPlayback() {
    if (this.playTimer) clearInterval(this.playTimer);
    this.playTimer = null;
  }

  /** Loads an imported project: switches preset, applies settings, regenerates (and refines if saved). */
  async loadProject(config: GenerationConfig, refine: RefineSettings | null, view?: { renderMode?: RenderMode; projection?: Projection }) {
    this.set({
      presetId: config.preset,
      seed: config.seed,
      paramsByPreset: { ...this.state.paramsByPreset, [config.preset]: { ...config.params } },
      overridesByPreset: { ...this.state.overridesByPreset, [config.preset]: { ...config.ruleOverrides } },
      ...(view?.renderMode ? { mode: view.renderMode } : {}),
      ...(view?.projection ? { projection: view.projection } : {}),
      ...(refine ? { refine: { ...refine } } : {}),
    });
    await this.generate({ ...config });
    if (refine && this.state.current?.config.seed === config.seed && !this.state.error) await this.refine();
  }

  destroy() {
    this.cancelFollow();
    this.stopPlayback();
  }
}

export function messageOf(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export const studio = new StudioStore();

export function useStudio<T>(select: (s: StudioState) => T): T {
  return useSyncExternalStore(studio.subscribe, () => select(studio.get()), () => select(studio.get()));
}
