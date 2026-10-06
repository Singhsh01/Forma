import { ArrowsLeftRight, Sparkle } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import { EnergyChart } from "./EnergyChart";
import { CheckList } from "./ChecksPanel";
import type { RefinementWire } from "../../lib/types";

function Slider({ id, label, min, max, step, value, onChange, help, fmt }: { id: string; label: string; min: number; max: number; step: number; value: number; onChange: (v: number) => void; help?: string; fmt?: (v: number) => string }) {
  const pct = ((value - min) / (max - min)) * 100;
  return (
    <div className="param">
      <div className="param-row">
        <label htmlFor={id} className="param-label">{label}</label>
        <output htmlFor={id} className="param-value num">{fmt ? fmt(value) : value}</output>
      </div>
      <input id={id} type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} style={{ ["--fill" as string]: `${pct}%` }} data-testid={id} />
      {help && <p className="param-help">{help}</p>}
    </div>
  );
}

function Results({ r }: { r: RefinementWire }) {
  const res = r.result;
  const keys = res.objectives.map((o) => o.key);
  const kept = r.kept === "best" ? res.best : res.final;
  const maxC = Math.max(1e-9, ...keys.map((k) => Math.max(res.initial.components[k] ?? 0, kept.components[k] ?? 0)));
  return (
    <section className="refine-results" aria-label="Refinement results">
      <h3 className="panel-subheading">Result</h3>
      <dl className="stat-row">
        <div><dt>Energy</dt><dd className="num">{res.initial.total.toFixed(3)} <span aria-hidden>→</span><span className="visually-hidden">to</span> {kept.total.toFixed(3)}</dd></div>
        <div><dt>Kept</dt><dd>{r.kept === "best" ? "lowest energy visited" : "final state"}</dd></div>
        <div><dt>Accepted</dt><dd className="num">{res.accepted} of {res.iterations}</dd></div>
        <div><dt>Uphill accepted</dt><dd className="num">{res.acceptedUphill}</dd></div>
        <div><dt>Rejected by constraints</dt><dd className="num">{res.rejectedByConstraint}</dd></div>
      </dl>
      <EnergyChart trace={res.trace} />
      <table className="energy-table">
        <caption className="visually-hidden">Energy by objective, before and after</caption>
        <thead>
          <tr><th scope="col">Objective</th><th scope="col">Weight</th><th scope="col">Before</th><th scope="col">After</th></tr>
        </thead>
        <tbody>
          {res.objectives.map((o) => {
            const b = res.initial.components[o.key] ?? 0, a = kept.components[o.key] ?? 0;
            return (
              <tr key={o.key}>
                <th scope="row">{o.label}</th>
                <td className="num">{(res.weights[o.key] ?? 1).toFixed(1)}</td>
                <td className="num"><span className="bar" style={{ width: `${(b / maxC) * 100}%` }} />{b.toFixed(3)}</td>
                <td className={"num" + (a < b - 1e-9 ? " better" : a > b + 1e-9 ? " worse" : "")}><span className="bar" style={{ width: `${(a / maxC) * 100}%` }} />{a.toFixed(3)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="panel-text">
        {res.mode === "mcmc" ? (
          <>Worse proposals are sometimes accepted: a move that raises the energy by ΔE is taken with probability e<sup>−ΔE/T</sup>. That lets the
          chain leave local optima; here {res.acceptedUphill} of the {res.accepted} accepted moves went uphill.</>
        ) : (
          <>Simulated annealing lowered the temperature from {res.temperature} to {res.temperatureEnd}, so uphill moves became rarer as the run
          progressed ({res.acceptedUphill} were accepted early on). This optimises rather than samples one fixed distribution.</>
        )}
      </p>
      <p className="panel-note">{r.target}</p>
      {res.recent.length > 0 && (
        <details className="moves">
          <summary>Last accepted moves</summary>
          <ol>
            {res.recent.slice().reverse().slice(0, 14).map((m, i) => (
              <li key={i}>
                <span className="num">#{m.iteration}</span> {m.move}{" "}
                <span className={"num " + (m.uphill ? "worse" : "better")}>ΔE {m.deltaE >= 0 ? "+" : ""}{m.deltaE.toFixed(3)}{m.uphill ? `, p ${m.p.toFixed(2)}` : ""}</span>
              </li>
            ))}
          </ol>
        </details>
      )}
      <h3 className="panel-subheading">Checks after refinement</h3>
      <CheckList checks={r.after} />
    </section>
  );
}

export function RefinePanel() {
  const refine = useStudio((s) => s.refine);
  const presets = useStudio((s) => s.presets);
  const presetId = useStudio((s) => s.presetId);
  const current = useStudio((s) => s.current);
  const previous = useStudio((s) => s.previous);
  const compare = useStudio((s) => s.compare);
  const busy = useStudio((s) => !!s.job);
  const online = useStudio((s) => s.presetsStatus === "ready");
  const preset = presets.find((p) => p.id === presetId);
  const objectives = preset?.objectives ?? [];
  const anneal = refine.mode === "anneal";
  return (
    <div className="refine">
      <p className="panel-intro">
        A Metropolis-Hastings sampler explores small, reversible edits to the massing: moving a volume, adding or removing a storey,
        toggling a roof garden or a link, widening a court. Each proposal is scored by the weighted objectives below. Hard constraints
        reject invalid states outright.
      </p>
      <fieldset className="param-group">
        <legend>Method</legend>
        <div className="segmented" role="radiogroup" aria-label="Sampling method">
          <button role="radio" aria-checked={!anneal} className={!anneal ? "is-on" : ""} onClick={() => studio.setRefine({ mode: "mcmc" })}>MCMC, fixed T</button>
          <button role="radio" aria-checked={anneal} className={anneal ? "is-on" : ""} onClick={() => studio.setRefine({ mode: "anneal" })}>Simulated annealing</button>
        </div>
        <Slider id="refine-temperature" label={anneal ? "Start temperature" : "Temperature"} min={0.02} max={2} step={0.01} value={refine.temperature} onChange={(v) => studio.setRefine({ temperature: v, temperatureEnd: Math.min(refine.temperatureEnd, v) })} help="Higher temperature accepts more worse proposals and explores more freely." fmt={(v) => v.toFixed(2)} />
        {anneal && <Slider id="refine-temperature-end" label="End temperature" min={0.01} max={refine.temperature} step={0.01} value={Math.min(refine.temperatureEnd, refine.temperature)} onChange={(v) => studio.setRefine({ temperatureEnd: v })} fmt={(v) => v.toFixed(2)} />}
        <Slider id="refine-iterations" label="Iterations" min={100} max={8000} step={100} value={refine.iterations} onChange={(v) => studio.setRefine({ iterations: v })} />
        <div className="param">
          <span className="param-label" id="keep-label">Keep</span>
          <div className="segmented" role="radiogroup" aria-labelledby="keep-label">
            <button role="radio" aria-checked={refine.keep === "best"} className={refine.keep === "best" ? "is-on" : ""} onClick={() => studio.setRefine({ keep: "best" })} data-testid="keep-best">Lowest energy visited</button>
            <button role="radio" aria-checked={refine.keep === "final"} className={refine.keep === "final" ? "is-on" : ""} onClick={() => studio.setRefine({ keep: "final" })} data-testid="keep-final">Final state</button>
          </div>
          <p className="param-help">A sampler wanders; its final state can be worse than the best one it passed through.</p>
        </div>
        <Slider id="refine-seed" label="Sampler seed" min={1} max={99} step={1} value={refine.mcmcSeed} onChange={(v) => studio.setRefine({ mcmcSeed: v })} help="With the same design, settings and sampler seed, the run is reproducible." />
      </fieldset>
      <fieldset className="param-group">
        <legend>Objective weights</legend>
        <p className="panel-text">These are stated design preferences, not measures of beauty. Zero ignores an objective.</p>
        {objectives.map((o) => (
          <Slider key={o.key} id={`w-${o.key}`} label={o.label} min={0} max={3} step={0.1} value={refine.weights[o.key] ?? 1} onChange={(v) => studio.setRefineWeight(o.key, v)} help={o.description} fmt={(v) => v.toFixed(1)} />
        ))}
      </fieldset>
      <button className="btn btn-primary btn-block" onClick={() => void studio.refine()} disabled={busy || !online || !current} data-testid="run-refine">
        <Sparkle size={15} weight="fill" /> Run refinement
      </button>
      {!online && <p className="panel-text">Refinement runs in the Java engine; start it to use this panel.</p>}
      {previous && current?.refinement && (
        <button className={"btn btn-secondary btn-block" + (compare ? " is-on" : "")} onClick={() => studio.toggleCompare()} aria-pressed={compare}>
          <ArrowsLeftRight size={15} weight="bold" /> {compare ? "Hide before/after" : "Compare before and after"}
        </button>
      )}
      {current?.refinement && <Results r={current.refinement} />}
    </div>
  );
}
