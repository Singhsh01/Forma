import { studio, useStudio } from "../../lib/studio";
import { FALLBACK_PRESETS } from "../../lib/presetsMeta";

export function PresetRail() {
  const presets = useStudio((s) => s.presets);
  const active = useStudio((s) => s.presetId);
  const busy = useStudio((s) => !!s.job);
  const list = presets.length ? presets.map((p) => ({ id: p.id, title: p.title, summary: p.summary })) : FALLBACK_PRESETS;
  return (
    <nav className="rail" aria-label="Worlds">
      <h2 className="panel-heading rail-heading">Worlds</h2>
      <ul className="rail-list">
        {list.map((p) => (
          <li key={p.id}>
            <button
              className={"rail-item" + (p.id === active ? " is-active" : "")}
              aria-current={p.id === active ? "true" : undefined}
              onClick={() => studio.selectPreset(p.id)}
              disabled={busy && p.id !== active}
              data-testid={`preset-${p.id}`}
            >
              <img src={`/thumbs/${p.id}.png`} alt="" loading="lazy" width={64} height={48} onError={(e) => ((e.target as HTMLImageElement).style.visibility = "hidden")} />
              <span className="rail-text">
                <span className="rail-title">{p.title}</span>
                <span className="rail-summary">{p.summary}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
