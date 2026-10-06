import type { MassingWire } from "../../lib/types";

/**
 * Shown instead of the 3D viewport when WebGL is unavailable: a plan of the massing (volumes,
 * voids, enabled links) so the studio's controls remain useful.
 */
export function PlanFallback({ massing, title }: { massing: MassingWire | null; title: string }) {
  return (
    <div className="plan-fallback" role="img" aria-label={`Plan of ${title}`}>
      <p className="plan-note">3D view unavailable: WebGL is disabled or unsupported in this browser. Showing the massing plan; every control still works.</p>
      {massing && (
        <svg viewBox="-40 -40 80 80" className="plan-svg">
          {massing.voids.map((v) => (
            <circle key={v.name} cx={v.cx} cy={v.cz} r={v.radius} fill="none" stroke="var(--patina)" strokeDasharray="1 1" strokeWidth={0.4} />
          ))}
          {massing.links.filter((l) => l.enabled && massing.volumes[l.a] && massing.volumes[l.b]).map((l, i) => {
            const a = massing.volumes[l.a], b = massing.volumes[l.b];
            return <line key={i} x1={a.cx} y1={a.cz} x2={b.cx} y2={b.cz} stroke="var(--lamplight)" strokeWidth={0.6} />;
          })}
          {massing.volumes.map((v) =>
            v.shape === "box" ? (
              <rect key={v.name} x={v.cx - v.w / 2} y={v.cz - v.d / 2} width={v.w} height={v.d} fill="none" stroke="var(--vellum-2)" strokeWidth={0.4}>
                <title>{v.name}</title>
              </rect>
            ) : (
              <g key={v.name}>
                <circle cx={v.cx} cy={v.cz} r={v.w / 2} fill="none" stroke="var(--vellum-2)" strokeWidth={0.4}><title>{v.name}</title></circle>
                {v.shape === "ring" && v.d > 0 && <circle cx={v.cx} cy={v.cz} r={v.d / 2} fill="none" stroke="var(--vellum-3)" strokeWidth={0.3} />}
              </g>
            ),
          )}
        </svg>
      )}
    </div>
  );
}
