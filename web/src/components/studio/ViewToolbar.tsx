import { Camera, Cloud, FilmSlate, ClockCounterClockwise, CubeTransparent, Cube } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import type { RenderMode } from "../../lib/types";

const MODES: { id: RenderMode; label: string }[] = [
  { id: "diorama", label: "Diorama" },
  { id: "clay", label: "Clay" },
  { id: "ink", label: "Ink" },
  { id: "blueprint", label: "Blueprint" },
];

export function ViewToolbar({ onReset }: { onReset: () => void }) {
  const mode = useStudio((s) => s.mode);
  const projection = useStudio((s) => s.projection);
  const quality = useStudio((s) => s.quality);
  const cinematic = useStudio((s) => s.cinematic);
  const clouds = useStudio((s) => s.clouds);
  const replayActive = useStudio((s) => s.replay.active);
  const replayLoading = useStudio((s) => s.replay.status === "loading");
  const hasScene = useStudio((s) => !!s.current);
  return (
    <div className="view-toolbar" role="toolbar" aria-label="View (display only, no regeneration)">
      <div className="segmented" role="radiogroup" aria-label="Rendering mode">
        {MODES.map((m) => (
          <button key={m.id} role="radio" aria-checked={mode === m.id} className={mode === m.id ? "is-on" : ""} onClick={() => studio.setView({ mode: m.id })} data-testid={`mode-${m.id}`}>
            {m.label}
          </button>
        ))}
      </div>
      <div className="toolbar-group">
        <button className={"icon-btn" + (projection === "orthographic" ? " is-on" : "")} aria-pressed={projection === "orthographic"} onClick={() => studio.setView({ projection: projection === "perspective" ? "orthographic" : "perspective" })} title={projection === "perspective" ? "Switch to isometric (orthographic)" : "Switch to perspective"} aria-label="Isometric projection" data-testid="toggle-projection">
          {projection === "perspective" ? <Cube size={16} weight="bold" /> : <CubeTransparent size={16} weight="bold" />}
        </button>
        <button className="icon-btn" onClick={onReset} title="Reset camera" aria-label="Reset camera"><Camera size={16} weight="bold" /></button>
        <button className={"icon-btn" + (cinematic ? " is-on" : "")} aria-pressed={cinematic} onClick={() => studio.setView({ cinematic: !cinematic })} title="Cinematic camera" aria-label="Cinematic camera"><FilmSlate size={16} weight="bold" /></button>
        <button className={"icon-btn" + (clouds ? " is-on" : "")} aria-pressed={clouds} onClick={() => studio.setView({ clouds: !clouds })} title="Clouds" aria-label="Show clouds"><Cloud size={16} weight="bold" /></button>
        <label className="quality">
          <span className="visually-hidden">Render quality</span>
          <select value={quality} onChange={(e) => studio.setView({ quality: e.target.value as "low" | "medium" | "high" })} aria-label="Render quality">
            <option value="low">Low</option>
            <option value="medium">Medium</option>
            <option value="high">High</option>
          </select>
        </label>
        <button
          className={"btn btn-secondary btn-small" + (replayActive ? " is-on" : "")}
          onClick={() => (replayActive ? studio.closeReplay() : void studio.openReplay())}
          disabled={!hasScene || replayLoading}
          aria-pressed={replayActive}
          data-testid="replay-toggle"
        >
          <ClockCounterClockwise size={15} weight="bold" /> {replayLoading ? "Loading…" : replayActive ? "Exit replay" : "Replay growth"}
        </button>
      </div>
    </div>
  );
}
