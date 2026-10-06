#!/usr/bin/env node
// Measures generation and refinement times through the running engine's HTTP API.
// Usage: start the engine (scripts/serve-engine.sh or start.sh), then: node scripts/measure.mjs [apiBase]
// Prints a Markdown table; numbers depend entirely on the machine it runs on.
import os from "node:os";

const api = process.argv[2] ?? "http://127.0.0.1:8080/api";
const presets = ["library", "cliffside", "gardens", "cathedral", "canal", "organic", "escher"];
const RUNS = 5;

async function post(path, body) {
  const r = await fetch(api + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error(`${path}: ${r.status} ${await r.text()}`);
  return r.json();
}
async function waitJob(id) {
  for (;;) {
    const s = await (await fetch(`${api}/jobs/${id}`)).json();
    if (["completed", "failed", "cancelled"].includes(s.status)) return s;
    await new Promise((r) => setTimeout(r, 25));
  }
}
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };

const health = await (await fetch(api + "/health")).json();
console.log(`Engine ${health.generatorVersion}; ${os.cpus().length} logical CPUs (${os.cpus()[0]?.model ?? "unknown"}), Node ${process.version}, ${new Date().toISOString().slice(0, 10)}`);
console.log(`Median of ${RUNS} warm runs after one warm-up, default parameters, server-side elapsed time per job.\n`);
console.log("| World | Runs | Generate (ms) | Engine stages (ms) | Instances | Scene JSON (KB) | Replay frames |");
console.log("|---|---|---|---|---|---|---|");
for (const preset of presets) {
  const times = [];
  let scene = null;
  for (let i = 0; i <= RUNS; i++) {
    const j = await post("/jobs/generate", { config: { preset, seed: 1 + i, params: {} } });
    const s = await waitJob(j.jobId);
    if (s.status !== "completed") throw new Error(`${preset}: ${s.status} ${s.error ?? ""}`);
    if (i > 0) times.push(s.elapsedMs);
    if (i === RUNS) {
      const text = await (await fetch(`${api}/jobs/${j.jobId}/scene`)).text();
      scene = { json: JSON.parse(text), kb: Math.round(text.length / 1024) };
    }
  }
  const st = scene.json.stats;
  console.log(`| ${preset} | ${RUNS} | ${median(times)} | compose ${st.composeMs}, realize ${st.realizeMs}, geometry ${st.sceneMs} | ${st.instances.toLocaleString("en")} | ${scene.kb} | ${st.historyFrames} |`);
}

// refinement: MCMC on the flagship
const base = await waitJob((await post("/jobs/generate", { config: { preset: "library", seed: 7, params: {} } })).jobId);
for (const iterations of [500, 2000, 8000]) {
  const r = await waitJob((await post("/jobs/refine", { baseJobId: base.jobId, refine: { iterations, temperature: 0.25, mode: "mcmc", mcmcSeed: 1, keep: "best" } })).jobId);
  const ref = await (await fetch(`${api}/jobs/${r.jobId}/refinement`)).json();
  console.log(`\nRefine library seed 7, ${iterations} MCMC iterations: job ${r.elapsedMs} ms total (sampler ${ref.result.millis} ms, rest is re-realising the design), accepted ${ref.result.accepted}.`);
}
