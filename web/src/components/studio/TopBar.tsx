import { ArrowsClockwise, Play, Stop } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import { Wordmark } from "../Wordmark";
import { Link } from "../Link";

export function TopBar() {
  const seed = useStudio((s) => s.seed);
  const job = useStudio((s) => s.job);
  const jobKind = useStudio((s) => s.jobKind);
  const status = useStudio((s) => s.presetsStatus);
  useStudio((s) => s.paramsByPreset);
  useStudio((s) => s.current);
  useStudio((s) => s.overridesByPreset);
  const dirty = studio.isDirty();
  const busy = !!job;
  const offline = status === "offline";

  return (
    <header className="topbar">
      <div className="topbar-left">
        <Wordmark compact />
        <span className="topbar-place">Studio</span>
      </div>
      <form
        className="topbar-run"
        onSubmit={(e) => {
          e.preventDefault();
          if (!busy) void studio.generate();
        }}
      >
        <label className="seed-field">
          <span>Seed</span>
          <input
            type="number"
            inputMode="numeric"
            min={0}
            value={seed}
            onChange={(e) => studio.setSeed(Number(e.target.value))}
            aria-describedby="seed-help"
            className="num"
          />
        </label>
        <span id="seed-help" className="visually-hidden">The seed fixes every random choice; the same seed and settings always give the same design.</span>
        <button type="button" className="icon-btn" onClick={() => studio.randomizeSeed()} title="New random seed" aria-label="New random seed" disabled={busy}>
          <ArrowsClockwise size={16} weight="bold" />
        </button>
        {busy ? (
          <button key="cancel" type="button" className="btn btn-secondary" onClick={(e) => { e.preventDefault(); void studio.cancel(); }} data-testid="cancel">
            <Stop size={15} weight="fill" /> Cancel
          </button>
        ) : (
          <button key="generate" type="submit" className="btn btn-primary" disabled={offline} data-testid="generate" title={offline ? "Start the Java engine to generate" : undefined}>
            <Play size={15} weight="fill" /> {dirty ? "Generate" : "Regenerate"}
          </button>
        )}
        <span className="topbar-hint" aria-live="polite">
          {busy ? (jobKind === "refine" ? "Refining…" : "Generating…") : offline ? "Engine offline" : dirty ? "Settings changed, not generated yet" : ""}
        </span>
      </form>
      <nav className="topbar-nav" aria-label="Site">
        <Link to="/how">How it grows</Link>
        <Link to="/">Exhibition</Link>
      </nav>
    </header>
  );
}
