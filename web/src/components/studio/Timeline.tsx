import { CaretLeft, CaretRight, Pause, Play } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";

const KIND_COLOR: Record<string, string> = {
  constructive: "var(--vellum-3)",
  rules: "var(--lamplight)",
  validation: "var(--patina)",
};

/** Recorded-stage timeline: segments per stage, play/pause, stepping and scrubbing. */
export function Timeline() {
  const r = useStudio((s) => s.replay);
  const m = r.model;
  if (!r.active || !m) return null;
  const n = m.frameCount;
  const stage = m.stageOfFrame(r.frame);
  return (
    <div className="timeline" role="group" aria-label="Growth replay">
      <div className="timeline-controls">
        <button className="icon-btn" onClick={() => studio.step(-1)} aria-label="Previous frame" disabled={r.frame <= 0}><CaretLeft size={16} weight="bold" /></button>
        {r.playing ? (
          <button className="icon-btn is-on" onClick={() => studio.pause()} aria-label="Pause" data-testid="replay-pause"><Pause size={16} weight="fill" /></button>
        ) : (
          <button className="icon-btn" onClick={() => studio.play()} aria-label="Play" data-testid="replay-play"><Play size={16} weight="fill" /></button>
        )}
        <button className="icon-btn" onClick={() => studio.step(1)} aria-label="Next frame" disabled={r.frame >= n - 1} data-testid="replay-next"><CaretRight size={16} weight="bold" /></button>
      </div>
      <div className="timeline-track">
        <div className="timeline-stages" aria-hidden>
          {m.stages.map((st, i) => (
            <button
              key={st.id}
              className={"timeline-stage" + (stage?.id === st.id ? " is-current" : "")}
              style={{ flexGrow: Math.max(1, st.lastFrame - st.firstFrame + 1), ["--c" as string]: KIND_COLOR[st.kind] ?? "var(--vellum-3)" }}
              onClick={() => studio.jumpToStage(i)}
              title={st.label}
              tabIndex={-1}
            >
              <span>{st.label}</span>
            </button>
          ))}
        </div>
        <input
          type="range"
          min={0}
          max={n - 1}
          value={r.frame}
          onChange={(e) => {
            studio.pause();
            studio.setFrame(Number(e.target.value));
          }}
          aria-label="Replay position"
          aria-valuetext={`${stage?.label ?? ""}, frame ${r.frame + 1} of ${n}`}
          data-testid="replay-scrub"
          style={{ ["--fill" as string]: `${(r.frame / Math.max(1, n - 1)) * 100}%` }}
        />
      </div>
      <p className="timeline-status num" aria-live="polite">
        <strong>{stage?.label}</strong> {m.frameLabels[r.frame] && m.frameLabels[r.frame] !== stage?.label ? `(${m.frameLabels[r.frame]})` : ""} frame {r.frame + 1}/{n}, {m.changesIn(r.frame).toLocaleString()} cells changed
      </p>
    </div>
  );
}
