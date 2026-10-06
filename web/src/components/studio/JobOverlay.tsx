import { useStudio } from "../../lib/studio";

const STAGE_LABEL: Record<string, string> = {
  queued: "Waiting for a worker",
  starting: "Starting",
  site: "Site and composition",
  masses: "Major masses",
  circulation: "Rooms and circulation",
  growth: "Rule-driven growth",
  validation: "Constraint checks",
  detailing: "Architectural detailing",
  refine: "Composition refinement",
  geometry: "Final geometry",
  mcmc: "Sampling refinements",
  realize: "Realising the refined massing",
  base: "Regenerating the base design",
};

/** Live job progress, driven by server-sent events. */
export function JobOverlay() {
  const job = useStudio((s) => s.job);
  if (!job) return null;
  const key = job.stage.startsWith("realize:") ? "realize" : job.stage;
  const label = STAGE_LABEL[key] ?? job.message;
  return (
    <div className="job" role="status" aria-live="polite" data-testid="job-overlay">
      <div className="job-row">
        <span className="job-title">{job.type === "refine" ? "Refining" : "Generating"} {job.preset}, seed {job.seed}</span>
        <span className="job-pct num">{Math.round(job.fraction * 100)}%</span>
      </div>
      <div className="job-bar" aria-hidden>
        <span style={{ transform: `scaleX(${Math.max(0.02, job.fraction)})` }} />
      </div>
      <p className="job-stage">{label}{job.type === "refine" && job.stage === "mcmc" ? `: ${job.message}` : ""}</p>
    </div>
  );
}
