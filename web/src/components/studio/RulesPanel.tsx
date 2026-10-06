import { useMemo, useState } from "react";
import { Check, PencilSimple, Warning, X } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import { api } from "../../lib/api";
import { b64ToBytes } from "../../lib/decode";
import { CellSwatch, RuleGlyph, SampleGlyph } from "../RuleGlyph";
import type { InspectorWire } from "../../lib/types";

function ProgramEditor({ program, source, preset, overridden }: { program: string; source: string; preset: string; overridden: boolean }) {
  const pending = useStudio((s) => s.overridesByPreset[s.presetId]?.[program]);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(pending ?? source);
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null);
  const online = useStudio((s) => s.presetsStatus === "ready");

  const apply = async () => {
    try {
      const r = await api.validateRules(preset, { [program]: text });
      if (!r.ok) {
        setStatus({ ok: false, msg: r.error ?? "invalid rule program" });
        return;
      }
      studio.setOverride(program, text);
      setStatus({ ok: true, msg: "Rules valid. Generate to grow the design with them." });
      setEditing(false);
    } catch (e) {
      setStatus({ ok: false, msg: (e as Error).message });
    }
  };

  return (
    <div className="program">
      <div className="program-head">
        <h4>
          <code>{program}</code>
          {overridden && <span className="tag">edited</span>}
          {pending !== undefined && !overridden && <span className="tag tag-pending">edited, not generated</span>}
        </h4>
        {!editing ? (
          <button className="btn btn-quiet btn-small" onClick={() => { setText(pending ?? source); setEditing(true); setStatus(null); }} disabled={!online} title={online ? undefined : "Start the engine to edit rules"}>
            <PencilSimple size={13} weight="bold" /> Edit rules
          </button>
        ) : null}
      </div>
      {editing ? (
        <>
          <label className="visually-hidden" htmlFor={`prog-${program}`}>Rule program {program}</label>
          <textarea id={`prog-${program}`} className="code-edit" value={text} onChange={(e) => setText(e.target.value)} spellCheck={false} rows={Math.min(22, text.split("\n").length + 2)} />
          <div className="program-actions">
            <button className="btn btn-primary btn-small" onClick={apply}><Check size={13} weight="bold" /> Validate and use</button>
            <button className="btn btn-quiet btn-small" onClick={() => setEditing(false)}><X size={13} weight="bold" /> Close</button>
            {pending !== undefined && (
              <button className="btn btn-quiet btn-small" onClick={() => { studio.setOverride(program, null); setText(source); setStatus({ ok: true, msg: "Restored the default rules." }); }}>
                Restore default
              </button>
            )}
          </div>
        </>
      ) : (
        <pre className="code-block">{source}</pre>
      )}
      {status && (
        <p className={"program-status " + (status.ok ? "is-ok" : "is-error")} role={status.ok ? "status" : "alert"}>
          {status.ok ? <Check size={13} weight="bold" /> : <Warning size={13} weight="bold" />} {status.msg}
        </p>
      )}
    </div>
  );
}

function ExecutedRules({ data, stage }: { data: InspectorWire; stage: string }) {
  const rules = data.rules.filter((r) => r.stage === stage);
  if (!rules.length) return null;
  return (
    <ul className="rule-list">
      {rules.map((r) => {
        const sample = r.sample
          ? { before: b64ToBytes(r.sample.before), after: b64ToBytes(r.sample.after), nx: r.sample.nx, ny: r.sample.ny, nz: r.sample.nz }
          : null;
        return (
          <li key={`${r.node}-${r.rule}`} className={"rule" + (r.applications === 0 ? " is-idle" : "")}>
            <div className="rule-head">
              <span className="rule-name"><code>{r.rule}</code> <span className="rule-node">in {r.nodeType} node “{r.node}”</span></span>
              <span className="rule-count num" title="times this rule actually fired">{r.applications.toLocaleString()}×</span>
            </div>
            <RuleGlyph input={r.input} output={r.output} cell={11} />
            {r.description && <p className="rule-desc">{r.description}</p>}
            <p className="rule-meta">
              {r.variants} symmetry variant{r.variants === 1 ? "" : "s"} ({r.symmetry}), p = {r.p}
            </p>
            {sample && (
              <div className="rule-sample">
                <span>First application, at cell ({r.sample!.x}, {r.sample!.y}, {r.sample!.z}):</span>
                <SampleGlyph {...sample} cell={8} />
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

export function RulesPanel() {
  const insp = useStudio((s) => s.inspector);
  const preset = useStudio((s) => s.presetId);
  const current = useStudio((s) => s.current);
  const [open, setOpen] = useState<string | null>(null);
  const data = insp.data;
  const stages = useMemo(() => data?.stages ?? [], [data]);
  if (insp.status === "loading" || insp.status === "idle") return <div className="skeleton-stack" aria-busy="true"><div /><div /><div /></div>;
  if (insp.status === "error" || !data) return <p className="panel-empty">Could not load the rule record: {insp.error}</p>;
  return (
    <div className="rules">
      <p className="panel-intro">
        Every stage of <strong>{current?.scene.wire.presetTitle}</strong>, in the order the engine ran it. Rule stages list the exact program
        executed and how often each rule fired; constructive stages list what code built.
      </p>
      <ol className="stage-list">
        {stages.map((st) => {
          const isOpen = open === st.id || (open === null && st.kind === "rules" && st.id === stages.find((s) => s.kind === "rules")?.id);
          return (
            <li key={st.id} className={"stage stage-" + st.kind + (isOpen ? " is-open" : "")}>
              <button className="stage-head" onClick={() => setOpen(isOpen ? "" : st.id)} aria-expanded={isOpen}>
                <span className="stage-label">{st.label}</span>
                <span className="stage-kind">{st.kind}</span>
                <span className="stage-ms num">{st.ms} ms</span>
              </button>
              {isOpen && (
                <div className="stage-body">
                  <p className="stage-desc">{st.description}</p>
                  {st.operations.length > 0 && (
                    <ul className="ops">
                      {st.operations.map((o, i) => <li key={i}>{o}</li>)}
                    </ul>
                  )}
                  {st.programs.map((p) => (
                    <ProgramEditor key={p.id} program={p.id} source={p.source} preset={preset} overridden={p.overridden} />
                  ))}
                  <ExecutedRules data={data} stage={st.id} />
                </div>
              )}
            </li>
          );
        })}
      </ol>
      <details className="legend">
        <summary>Cell legend</summary>
        <ul className="legend-list">
          {data.states.filter((s) => s.name !== "air").map((s) => (
            <li key={s.symbol}>
              <CellSwatch symbol={s.symbol} size={11} />
              <code>{s.symbol}</code> {s.name}
            </li>
          ))}
          {Object.entries(data.legendUnions).map(([k, v]) => (
            <li key={k}><code>{k}</code> any of: {v}</li>
          ))}
          <li><code>*</code> input: anything; output: leave unchanged</li>
        </ul>
      </details>
    </div>
  );
}
