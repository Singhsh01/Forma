import { decodeScene } from "./decode";
import type {
  GenerationConfig, InspectorWire, JobStatus, PresetsResponse, RefineSettings, RefinementWire, ReplayWire, Scene, SceneWire,
} from "./types";

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

const BASE = "/api";

async function request<T>(path: string, init?: RequestInit, signal?: AbortSignal): Promise<{ data: T; bytes: number }> {
  let res: Response;
  try {
    res = await fetch(BASE + path, { ...init, signal, headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) } });
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e;
    throw new ApiError("Can't reach the FORMA engine. Start the Java server (scripts/start.sh or start.ps1) and try again.", 0);
  }
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(res.ok ? "The engine sent a response that is not JSON." : `Request failed (${res.status}).`, res.status);
  }
  if (!res.ok) {
    const msg = (body as { error?: string } | null)?.error ?? `Request failed (${res.status}).`;
    throw new ApiError(msg, res.status);
  }
  return { data: body as T, bytes: text.length };
}

export const api = {
  async health() {
    return (await request<{ status: string; generatorVersion: string }>("/health")).data;
  },
  async presets() {
    return (await request<PresetsResponse>("/presets")).data;
  },
  async programs(preset: string, params: Record<string, number>) {
    return (await request<{ programs: Record<string, string> }>("/programs", { method: "POST", body: JSON.stringify({ preset, params }) })).data.programs;
  },
  async validateRules(preset: string, overrides: Record<string, string>) {
    return (await request<{ ok: boolean; error?: string }>("/validate-rules", { method: "POST", body: JSON.stringify({ preset, overrides }) })).data;
  },
  async generate(config: GenerationConfig) {
    return (await request<JobStatus>("/jobs/generate", { method: "POST", body: JSON.stringify(config) })).data;
  },
  async refine(baseJobId: string | null, config: GenerationConfig, settings: RefineSettings) {
    return (await request<JobStatus>("/jobs/refine", { method: "POST", body: JSON.stringify({ baseJobId, config, refine: settings }) })).data;
  },
  async status(jobId: string) {
    return (await request<JobStatus>(`/jobs/${jobId}`)).data;
  },
  async cancel(jobId: string) {
    return (await request<JobStatus>(`/jobs/${jobId}`, { method: "DELETE" })).data;
  },
  async scene(jobId: string, signal?: AbortSignal): Promise<Scene> {
    const { data, bytes } = await request<SceneWire>(`/jobs/${jobId}/scene`, undefined, signal);
    return decodeScene(data, bytes);
  },
  async replay(jobId: string, signal?: AbortSignal) {
    return (await request<ReplayWire>(`/jobs/${jobId}/replay`, undefined, signal)).data;
  },
  async inspector(jobId: string, signal?: AbortSignal) {
    return (await request<InspectorWire>(`/jobs/${jobId}/inspector`, undefined, signal)).data;
  },
  async refinement(jobId: string, signal?: AbortSignal) {
    return (await request<RefinementWire>(`/jobs/${jobId}/refinement`, undefined, signal)).data;
  },
};

/**
 * Follows a job until it finishes. Uses Server-Sent Events (the browser reconnects with
 * Last-Event-ID automatically); if the stream fails repeatedly it falls back to polling.
 * Resolves with the terminal status. Call the returned `stop` to detach early.
 */
export function followJob(jobId: string, onUpdate: (s: JobStatus) => void): { done: Promise<JobStatus>; stop: () => void } {
  let stopped = false;
  let es: EventSource | null = null;
  let poll: ReturnType<typeof setTimeout> | null = null;
  let resolveFn: (s: JobStatus) => void = () => {};
  let rejectFn: (e: unknown) => void = () => {};
  const done = new Promise<JobStatus>((res, rej) => {
    resolveFn = res;
    rejectFn = rej;
  });
  const terminal = (s: JobStatus) => s.status === "completed" || s.status === "failed" || s.status === "cancelled";
  const finish = (s: JobStatus) => {
    if (stopped) return;
    stopped = true;
    es?.close();
    if (poll) clearTimeout(poll);
    resolveFn(s);
  };
  const handle = (raw: string) => {
    try {
      const s = JSON.parse(raw) as JobStatus;
      onUpdate(s);
      if (terminal(s)) finish(s);
    } catch {
      /* ignore malformed event */
    }
  };
  const startPolling = () => {
    const tick = async () => {
      if (stopped) return;
      try {
        const s = await api.status(jobId);
        onUpdate(s);
        if (terminal(s)) return finish(s);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          stopped = true;
          return rejectFn(e);
        }
      }
      poll = setTimeout(tick, 400);
    };
    tick();
  };
  if (typeof EventSource === "undefined") startPolling();
  else {
    let failures = 0;
    es = new EventSource(`${BASE}/jobs/${jobId}/events`);
    for (const t of ["status", "progress", "done"]) es.addEventListener(t, (ev) => handle((ev as MessageEvent).data));
    es.onerror = () => {
      failures++;
      if (failures >= 3 && !stopped) {
        es?.close();
        es = null;
        startPolling();
      }
    };
  }
  return {
    done,
    stop: () => {
      stopped = true;
      es?.close();
      if (poll) clearTimeout(poll);
    },
  };
}
