/** Energy over the sampler run, drawn as a single line (no filled track; the shape is the data). */
export function EnergyChart({ trace, width = 300, height = 90, label }: { trace: number[]; width?: number; height?: number; label?: string }) {
  if (trace.length < 2) return null;
  const min = Math.min(...trace), max = Math.max(...trace);
  const span = max - min || 1;
  const px = (i: number) => (i / (trace.length - 1)) * (width - 8) + 4;
  const py = (v: number) => height - 6 - ((v - min) / span) * (height - 16);
  const d = trace.map((v, i) => `${i === 0 ? "M" : "L"}${px(i).toFixed(1)} ${py(v).toFixed(1)}`).join(" ");
  return (
    <figure className="energy-chart">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label ?? `Energy from ${trace[0].toFixed(2)} to ${trace[trace.length - 1].toFixed(2)}`}>
        <line x1={4} x2={width - 4} y1={py(min)} y2={py(min)} stroke="var(--line-2)" strokeDasharray="2 3" />
        <path d={d} fill="none" stroke="var(--lamplight)" strokeWidth={1.6} strokeLinejoin="round" />
        <circle cx={px(trace.length - 1)} cy={py(trace[trace.length - 1])} r={2.6} fill="var(--lamplight)" />
      </svg>
      <figcaption className="num">
        <span>start {trace[0].toFixed(3)}</span>
        <span>lowest seen {min.toFixed(3)}</span>
        <span>end {trace[trace.length - 1].toFixed(3)}</span>
      </figcaption>
    </figure>
  );
}
