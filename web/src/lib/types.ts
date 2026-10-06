// Typed contracts for the FORMA HTTP API. Keep in sync with engine io/Serializers.java and docs/API.md.

export interface ParamSpec {
  key: string;
  label: string;
  help: string;
  kind: "int" | "float" | "bool";
  min: number;
  max: number;
  step: number;
  default: number;
  group: string;
}

export interface ObjectiveSpec {
  key: string;
  label: string;
  description: string;
}

export interface PresetInfo {
  id: string;
  title: string;
  summary: string;
  notes: string[];
  params: ParamSpec[];
  programs: string[];
  grid: [number, number, number];
  objectives: ObjectiveSpec[];
}

export interface PresetsResponse {
  generatorVersion: string;
  presets: PresetInfo[];
}

export interface GenerationConfig {
  preset: string;
  seed: number;
  params: Record<string, number>;
  ruleOverrides: Record<string, string>;
  generatorVersion?: string;
}

export type JobStatusName = "queued" | "running" | "completed" | "failed" | "cancelled";

export interface JobStatus {
  jobId: string;
  type: "generate" | "refine";
  status: JobStatusName;
  stage: string;
  fraction: number;
  message: string;
  preset: string;
  seed: number;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  elapsedMs?: number;
  error?: string;
  baseJobId?: string;
}

export interface SceneGroupWire {
  kind: string;
  material: string;
  count: number;
  data: string; // base64 Float32 x 9 per instance: cx cy cz sx sy sz rotY tilt variation
  comp: string; // base64 Uint16 per instance
}

export interface ComponentInfo {
  id: number;
  name: string;
  kind: string;
  material: string;
  floating: boolean;
}

export interface SceneWire {
  schema: string;
  generatorVersion: string;
  jobId?: string;
  config: GenerationConfig;
  resolvedParams: Record<string, number>;
  presetTitle: string;
  grid: { sx: number; sy: number; sz: number; cellMeters: number; storeyCells: number; groundLevel: number };
  units: string;
  camera: { target: [number, number, number]; distance: number; azimuth: number; elevation: number };
  atmosphere: { cloudLevel: number; fantasy: boolean; cloudy: boolean; sky?: string; projection?: string };
  bounds: [number, number, number, number, number, number];
  components: ComponentInfo[];
  groups: SceneGroupWire[];
  stats: {
    instances: number;
    groups: number;
    cells: number;
    composeMs: number;
    realizeMs: number;
    sceneMs: number;
    totalMs: number;
    ruleSteps: number;
    historyFrames: number;
    historyChanges: number;
  };
  provenance?: Record<string, unknown>;
}

export interface SceneGroup {
  kind: string;
  material: string;
  count: number;
  data: Float32Array;
  comp: Uint16Array;
}

export interface Scene {
  wire: Omit<SceneWire, "groups">;
  groups: SceneGroup[];
  bytes: number;
}

export interface StageInfo {
  id: string;
  label: string;
  kind: string;
  description: string;
  firstFrame: number;
  lastFrame: number;
}

export interface ReplayWire {
  schema: string;
  grid: { sx: number; sy: number; sz: number };
  states: string[];
  stages: StageInfo[];
  frames: { stage: number; label: string; n: number; idx: string; val: string }[];
  totalChanges: number;
  recordedChanges: number;
}

export interface RuleSample {
  x: number; y: number; z: number;
  nx: number; ny: number; nz: number;
  variant: number;
  before: string;
  after: string;
}

export interface RuleRecord {
  stage: string;
  node: string;
  nodeType: string;
  rule: string;
  description: string;
  input: string;
  output: string;
  symmetry: string;
  p: number;
  variants: number;
  applications: number;
  firstStep: number;
  lastStep: number;
  sample?: RuleSample;
}

export interface CheckResult {
  id: string;
  label: string;
  kind: "hard" | "heuristic" | "info";
  status: "pass" | "warn" | "fail";
  detail: string;
  value: number;
}

export interface MassingWire {
  groundLevel: number;
  volumes: { name: string; role: string; shape: string; cx: number; cz: number; w: number; d: number; y0: number; storeys: number; terraced: boolean; courtyard: number; floating: boolean }[];
  links: { a: number; b: number; level: number; kind: string; enabled: boolean }[];
  voids: { name: string; cx: number; cz: number; radius: number }[];
}

export interface InspectorWire {
  stages: (StageInfo & { ms: number; operations: string[]; programs: { id: string; source: string; overridden: boolean }[] })[];
  rules: RuleRecord[];
  nodes: { stage: string; node: string; type: string; steps: number; exhausted: boolean }[];
  checks: CheckResult[];
  massing: MassingWire;
  graph: {
    nodes: { id: number; name: string; kind: string; cells: number; reachable: boolean; x: number; y: number; z: number }[];
    edges: { a: number; b: number; via: string; crossings: number }[];
  };
  states: { name: string; symbol: string }[];
  legendUnions: Record<string, string>;
  histogram: Record<string, number>;
}

export interface EnergyWire {
  total: number;
  components: Record<string, number>;
}

export interface RefinementWire {
  baseJobId: string | null;
  mcmcSeed: number;
  kept?: "best" | "final";
  result: {
    mode: "mcmc" | "simulated-annealing";
    temperature: number;
    temperatureEnd: number;
    iterationsRequested: number;
    iterations: number;
    accepted: number;
    acceptedUphill: number;
    rejectedByConstraint: number;
    acceptanceRate: number;
    cancelled: boolean;
    millis: number;
    constraintRejections: Record<string, number>;
    weights: Record<string, number>;
    objectives: { key: string; label: string }[];
    initial: EnergyWire;
    final: EnergyWire;
    best: EnergyWire;
    trace: number[];
    recent: { iteration: number; move: string; deltaE: number; p: number; uphill: boolean; energy: number }[];
  };
  before: CheckResult[];
  after: CheckResult[];
  massingBefore: MassingWire;
  massingAfter: MassingWire;
  target: string;
}

export interface RefineSettings {
  iterations: number;
  temperature: number;
  temperatureEnd: number;
  mode: "mcmc" | "anneal";
  weights: Record<string, number>;
  mcmcSeed: number;
  /** Which state of the chain becomes the refined design: the lowest-energy state visited, or where the chain ended. */
  keep: "best" | "final";
}

export type RenderMode = "diorama" | "clay" | "ink" | "blueprint";

/** Exported project file (JSON). */
export interface ProjectFile {
  format: "forma-project/1";
  name: string;
  savedAt: string;
  generatorVersion: string;
  config: GenerationConfig;
  refine?: RefineSettings | null;
  view?: { renderMode: RenderMode; projection: "perspective" | "orthographic" };
}
