import { ArrowCounterClockwise, Info } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import type { ParamSpec } from "../../lib/types";

const GROUP_LABEL: Record<string, string> = {
  massing: "Massing",
  circulation: "Circulation",
  detail: "Detail",
  mode: "Mode",
  terrain: "Terrain",
  water: "Water",
};

function fmt(s: ParamSpec, v: number) {
  if (s.kind === "int") return String(Math.round(v));
  if (s.kind === "bool") return v >= 0.5 ? "On" : "Off";
  return v.toFixed(s.step < 0.1 ? 2 : 1);
}

function Control({ spec, value, dirty }: { spec: ParamSpec; value: number; dirty: boolean }) {
  const id = `param-${spec.key}`;
  if (spec.kind === "bool") {
    return (
      <div className={"param param-bool" + (dirty ? " is-dirty" : "")}>
        <label htmlFor={id} className="param-label">{spec.label}</label>
        <button
          id={id}
          role="switch"
          aria-checked={value >= 0.5}
          className={"switch" + (value >= 0.5 ? " is-on" : "")}
          onClick={() => studio.setParam(spec.key, value >= 0.5 ? 0 : 1)}
          aria-describedby={`${id}-help`}
          data-testid={id}
        >
          <span className="switch-knob" />
        </button>
        <p id={`${id}-help`} className="param-help">{spec.help}</p>
      </div>
    );
  }
  const pct = ((value - spec.min) / (spec.max - spec.min)) * 100;
  return (
    <div className={"param" + (dirty ? " is-dirty" : "")}>
      <div className="param-row">
        <label htmlFor={id} className="param-label">{spec.label}</label>
        <output htmlFor={id} className="param-value num">{fmt(spec, value)}</output>
      </div>
      <input
        id={id}
        type="range"
        min={spec.min}
        max={spec.max}
        step={spec.step}
        value={value}
        onChange={(e) => studio.setParam(spec.key, Number(e.target.value))}
        style={{ ["--fill" as string]: `${pct}%` }}
        aria-describedby={`${id}-help`}
        data-testid={id}
      />
      <p id={`${id}-help`} className="param-help">{spec.help}</p>
    </div>
  );
}

export function ParamPanel() {
  useStudio((s) => s.paramsByPreset);
  const presetId = useStudio((s) => s.presetId);
  const current = useStudio((s) => s.current);
  const presets = useStudio((s) => s.presets);
  const preset = presets.find((p) => p.id === presetId);
  const params = studio.params();
  if (!preset) {
    return <p className="panel-empty">Parameters appear when the Java engine is running. You can still explore the precomputed design.</p>;
  }
  const groups = new Map<string, ParamSpec[]>();
  preset.params.forEach((p) => groups.set(p.group, [...(groups.get(p.group) ?? []), p]));
  const applied = current?.config.preset === presetId ? current.config.params : null;
  return (
    <div className="params">
      <div className="panel-intro">
        <p>{preset.summary}</p>
        <p className="panel-note">
          <Info size={14} weight="bold" aria-hidden /> These settings change the geometry, so they apply when you generate. View controls over the
          viewport change only the display.
        </p>
      </div>
      {[...groups.entries()].map(([g, specs]) => (
        <fieldset key={g} className="param-group">
          <legend>{GROUP_LABEL[g] ?? g}</legend>
          {specs.map((s) => (
            <Control key={s.key} spec={s} value={params[s.key]} dirty={applied ? Math.abs((applied[s.key] ?? s.default) - params[s.key]) > 1e-9 : false} />
          ))}
        </fieldset>
      ))}
      <button className="btn btn-quiet" onClick={() => studio.resetParams()}>
        <ArrowCounterClockwise size={14} weight="bold" /> Reset to defaults
      </button>
      {preset.notes.length > 0 && (
        <div className="preset-notes">
          <h3 className="panel-subheading">About this world</h3>
          <ul>
            {preset.notes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
