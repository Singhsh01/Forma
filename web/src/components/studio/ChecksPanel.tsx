import { CheckCircle, Info, WarningCircle, XCircle } from "@phosphor-icons/react";
import { useStudio } from "../../lib/studio";
import type { CheckResult, InspectorWire } from "../../lib/types";

export function StatusIcon({ status }: { status: CheckResult["status"] }) {
  if (status === "pass") return <CheckCircle size={16} weight="fill" className="st-pass" aria-label="passed" />;
  if (status === "warn") return <WarningCircle size={16} weight="fill" className="st-warn" aria-label="warning" />;
  return <XCircle size={16} weight="fill" className="st-fail" aria-label="failed" />;
}

const KIND: Record<string, string> = { hard: "Requirement", heuristic: "Heuristic", info: "Information" };

export function CheckList({ checks }: { checks: CheckResult[] }) {
  return (
    <ul className="check-list">
      {checks.map((c) => (
        <li key={c.id} className={"check is-" + c.status}>
          <StatusIcon status={c.status} />
          <div>
            <p className="check-label">
              {c.label} <span className="check-kind">{KIND[c.kind] ?? c.kind}</span>
            </p>
            <p className="check-detail">{c.detail}</p>
          </div>
        </li>
      ))}
    </ul>
  );
}

const VIA_COLOR: Record<string, string> = {
  door: "var(--vellum-3)",
  bridge: "var(--patina)",
  stair: "var(--lamplight)",
  core: "var(--vellum-4)",
  open: "var(--vellum-4)",
};

/** Plan view of the derived room and circulation graph (positions are component centroids). */
export function GraphPlan({ graph, size = 300 }: { graph: InspectorWire["graph"]; size?: number }) {
  if (!graph.nodes.length) return null;
  const xs = graph.nodes.map((n) => n.x), zs = graph.nodes.map((n) => n.z);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs);
  const span = Math.max(maxX - minX, maxZ - minZ, 1);
  const pad = 18;
  const sc = (size - pad * 2) / span;
  const px = (x: number) => pad + (x - minX) * sc + ((span - (maxX - minX)) * sc) / 2;
  const pz = (z: number) => pad + (z - minZ) * sc + ((span - (maxZ - minZ)) * sc) / 2;
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  return (
    <svg viewBox={`0 0 ${size} ${size}`} className="graph-plan" role="img" aria-label="Room and circulation graph in plan">
      {graph.edges.map((e, i) => {
        const a = byId.get(e.a), b = byId.get(e.b);
        if (!a || !b) return null;
        return (
          <line key={i} x1={px(a.x)} y1={pz(a.z)} x2={px(b.x)} y2={pz(b.z)} stroke={VIA_COLOR[e.via] ?? "var(--vellum-4)"} strokeWidth={e.via === "bridge" ? 2 : 1.2} strokeDasharray={e.via === "door" ? "3 2" : undefined}>
            <title>{`${a.name} to ${b.name} via ${e.via}`}</title>
          </line>
        );
      })}
      {graph.nodes.map((n) => (
        <g key={n.id}>
          <circle cx={px(n.x)} cy={pz(n.z)} r={Math.max(3, Math.min(9, Math.sqrt(n.cells) / 4))} fill={n.reachable ? "var(--night-750)" : "var(--terracotta)"} stroke="var(--vellum-2)" strokeWidth={1} />
          <title>{`${n.name}: ${n.cells} walkable cells${n.reachable ? "" : " (unreachable)"}`}</title>
        </g>
      ))}
    </svg>
  );
}

export function ChecksPanel() {
  const insp = useStudio((s) => s.inspector);
  if (insp.status === "loading" || insp.status === "idle") return <div className="skeleton-stack" aria-busy="true"><div /><div /></div>;
  if (insp.status === "error" || !insp.data) return <p className="panel-empty">Could not load the checks: {insp.error}</p>;
  const d = insp.data;
  const vias = d.graph.edges.reduce<Record<string, number>>((acc, e) => ((acc[e.via] = (acc[e.via] ?? 0) + 1), acc), {});
  return (
    <div className="checks">
      <CheckList checks={d.checks} />
      <p className="panel-note">
        <Info size={14} weight="bold" aria-hidden /> Support and daylight are design heuristics for a conceptual generator, not structural analysis
        or building-code checks. Local rules never guarantee global connectivity, so circulation is verified by walking the finished grid.
      </p>
      <section className="graph-section">
        <h3 className="panel-subheading">Room and circulation graph</h3>
        <p className="panel-text">
          {d.graph.nodes.length} spaces, {d.graph.edges.length} connections
          {Object.keys(vias).length > 0 && " (" + Object.entries(vias).map(([k, v]) => `${v} ${k}`).join(", ") + ")"}. Derived from the finished geometry.
        </p>
        <GraphPlan graph={d.graph} />
        <ul className="graph-key">
          <li><span style={{ background: "var(--patina)" }} /> bridge</li>
          <li><span style={{ background: "var(--lamplight)" }} /> stair</li>
          <li><span className="dashed" /> door</li>
        </ul>
      </section>
    </div>
  );
}
