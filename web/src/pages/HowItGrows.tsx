import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "../components/Link";
import { Mark } from "../components/Wordmark";
import { RuleGlyph, SampleGlyph } from "../components/RuleGlyph";
import { CheckList, GraphPlan } from "../components/studio/ChecksPanel";
import { EnergyChart } from "../components/studio/EnergyChart";
import { PlanFallback } from "../components/studio/PlanFallback";
import { Viewer, webglAvailable } from "../viewer/Viewer";
import { ReplayModel } from "../lib/replay";
import { b64ToBytes } from "../lib/decode";
import { loadStaticInspector, loadStaticRefinement, loadStaticRefinedScene, loadStaticReplay, loadStaticScene } from "../lib/staticScenes";
import { useReducedMotion } from "../lib/useReducedMotion";
import type { InspectorWire, RefinementWire, RenderMode, RuleRecord, Scene } from "../lib/types";
import "../styles/landing.css";
import "../styles/how.css";

type Chapter = "compose" | "grow" | "check" | "detail" | "refine" | "render";

const CHAPTERS: { id: Chapter; title: string; stages: string[] }[] = [
  { id: "compose", title: "Compose", stages: ["site", "masses", "circulation"] },
  { id: "grow", title: "Grow", stages: ["growth"] },
  { id: "check", title: "Check", stages: ["validation"] },
  { id: "detail", title: "Detail", stages: ["detailing", "refine"] },
  { id: "refine", title: "Refine", stages: [] },
  { id: "render", title: "Render", stages: [] },
];

function RuleCards({ rules }: { rules: RuleRecord[] }) {
  return (
    <ul className="how-rules">
      {rules.map((r) => (
        <li key={r.node + r.rule}>
          <div className="how-rule-top">
            <code>{r.rule}</code>
            <span className="num">{r.applications.toLocaleString()} applications</span>
          </div>
          <RuleGlyph input={r.input} output={r.output} cell={14} />
          <p>{r.description || `${r.nodeType} node ${r.node}`}</p>
          {r.sample && (
            <div className="how-sample">
              <span>One real application, before and after</span>
              <SampleGlyph before={b64ToBytes(r.sample.before)} after={b64ToBytes(r.sample.after)} nx={r.sample.nx} ny={r.sample.ny} nz={r.sample.nz} cell={9} />
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

export function HowItGrows() {
  const reduced = useReducedMotion();
  const [gl] = useState(() => webglAvailable());
  const [scene, setScene] = useState<Scene | null>(null);
  const [refined, setRefined] = useState<Scene | null>(null);
  const [insp, setInsp] = useState<InspectorWire | null>(null);
  const [ref, setRef] = useState<RefinementWire | null>(null);
  const [model, setModel] = useState<ReplayModel | null>(null);
  const [active, setActive] = useState<Chapter>("compose");
  const [frame, setFrame] = useState(0);
  const [renderMode, setRenderMode] = useState<RenderMode>("diorama");
  const [showRefined, setShowRefined] = useState(true);
  const sections = useRef<Record<string, HTMLElement | null>>({});

  useEffect(() => {
    loadStaticScene("library").then(setScene, () => {});
    loadStaticRefinedScene("library").then(setRefined, () => {});
    loadStaticInspector("library").then(setInsp, () => {});
    loadStaticRefinement("library").then(setRef, () => {});
    loadStaticReplay("library").then((w) => setModel(new ReplayModel(w)), () => {});
  }, []);

  // which chapter is in view drives the sticky viewer
  useEffect(() => {
    const io = new IntersectionObserver(
      (entries) => {
        const vis = entries.filter((e) => e.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (vis) setActive((vis.target as HTMLElement).dataset.chapter as Chapter);
      },
      { rootMargin: "-35% 0px -45% 0px", threshold: [0, 0.25, 0.5, 1] },
    );
    Object.values(sections.current).forEach((el) => el && io.observe(el));
    return () => io.disconnect();
  }, [insp]);

  // animate the replay through the frames of the active chapter's stages
  const range = useMemo(() => {
    if (!model) return null;
    const ch = CHAPTERS.find((c) => c.id === active)!;
    const idx = model.stages.map((s, i) => (ch.stages.includes(s.id) ? i : -1)).filter((i) => i >= 0);
    if (!idx.length) return null;
    const first = model.stages[idx[0]].firstFrame;
    const last = model.stages[idx[idx.length - 1]].lastFrame;
    return { from: Math.max(0, first - 1), to: last };
  }, [model, active]);

  useEffect(() => {
    if (!range) return;
    if (reduced) {
      setFrame(range.to);
      return;
    }
    let f = range.from;
    setFrame(f);
    const step = Math.max(1, Math.round((range.to - range.from) / 40));
    const t = setInterval(() => {
      f = Math.min(range.to, f + step);
      setFrame(f);
      if (f >= range.to) clearInterval(t);
    }, 60);
    return () => clearInterval(t);
  }, [range, reduced]);

  const replay = model && range ? { model, frame } : null;
  const viewerScene = active === "refine" ? (showRefined && refined ? refined : scene) : scene;
  const stage = (id: string) => insp?.stages.find((s) => s.id === id);
  const rulesOf = (id: string) => (insp?.rules ?? []).filter((r) => r.stage === id && r.applications > 0);
  const res = ref?.result;

  return (
    <div className="how">
      <header className="site-nav how-nav">
        <Link to="/" className="wordmark" aria-label="FORMA home">
          <Mark size={18} />
          <span className="wordmark-text">FORMA</span>
        </Link>
        <nav aria-label="Site">
          <Link to="/">Exhibition</Link>
          <Link to="/studio" className="nav-studio">Studio</Link>
        </nav>
      </header>

      <div className="how-intro">
        <h1>How a world grows</h1>
        <p>
          This is the flagship, The Library Above the Clouds (seed 7), taken apart in the order the Java engine built it. The viewer on the right
          replays the recorded history: every frame is a batch of real cell changes, not an animation made afterwards.
        </p>
      </div>

      <div className="how-layout">
        <div className="how-chapters">
          <section ref={(el) => { sections.current.compose = el; }} data-chapter="compose" className="chapter">
            <h2>Compose</h2>
            <p className="chapter-lede">Seeded code lays out the site and the major masses. Nothing here is random noise: the composition has a centre.</p>
            <p>
              The preset turns its parameters and the seed into a <em>massing</em>: an atrium tower with a reserved open void, concentric reading
              rings that step down as terraces, satellite towers on rock outcrops, candidate sky bridges and cloud platforms. That small state is
              what the sampler later refines. It is rasterised into a grid of {scene ? `${scene.wire.grid.sx} × ${scene.wire.grid.sy} × ${scene.wire.grid.sz}` : "…"} cells of 1.5 m,
              two cells to a storey, as storeyed shells: floors, piers and window bays in a rhythm aligned across storeys.
            </p>
            {insp && (
              <>
                <figure className="chapter-figure">
                  <div className="mini-plan"><PlanFallback massing={insp.massing} title="the flagship" /></div>
                  <figcaption>The massing in plan: volumes, the atrium void (dashed) and enabled bridges.</figcaption>
                </figure>
                <ul className="ops-list">
                  {["site", "masses", "circulation"].flatMap((id) => stage(id)?.operations.slice(0, 5) ?? []).map((o, i) => <li key={i}>{o}</li>)}
                </ul>
              </>
            )}
          </section>

          <section ref={(el) => { sections.current.grow = el; }} data-chapter="grow" className="chapter">
            <h2>Grow</h2>
            <p className="chapter-lede">Rewrite rules take over. Each finds a pattern of cells and replaces it, in an order the program fixes.</p>
            <p>
              A <em>prl</em> node applies every match independently with probability p; an <em>all</em> node applies a maximal set of
              non-overlapping matches; a <em>one</em> node applies one randomly chosen match per step. Patterns are tried in every rotation about
              the vertical axis and, where allowed, mirrored. Here the rules close bays pressed against neighbouring buildings, turn stacked bays
              into tall lit windows, and grow gardens across the terraces by Eden growth.
            </p>
            {stage("growth")?.programs[0] && <pre className="how-code">{stage("growth")!.programs[0].source}</pre>}
            <RuleCards rules={rulesOf("growth").slice(0, 5)} />
          </section>

          <section ref={(el) => { sections.current.check = el; }} data-chapter="check" className="chapter">
            <h2>Check</h2>
            <p className="chapter-lede">Local rules cannot promise global properties, so the engine checks the finished grid.</p>
            <p>
              A pedestrian graph is walked from the entrance: steps between slabs with headroom, stairs that rise one cell per cell of run,
              circulation cores. Every required space must be reached. Reserved voids must stay open to the sky; bridges need clear headroom. In
              grounded mode a support heuristic follows load paths down to the rock with limited cantilevers and spans. These are design
              heuristics for a conceptual generator, not structural analysis or building-code validation.
            </p>
            {insp && <CheckList checks={insp.checks} />}
            {insp && (
              <figure className="chapter-figure">
                <GraphPlan graph={insp.graph} size={320} />
                <figcaption>The room and circulation graph derived from the geometry: {insp.graph.nodes.length} spaces and {insp.graph.edges.length} connections. Bridges in patina, stairs in lamplight, doors dashed.</figcaption>
              </figure>
            )}
          </section>

          <section ref={(el) => { sections.current.detail = el; }} data-chapter="detail" className="chapter">
            <h2>Detail</h2>
            <p className="chapter-lede">Smaller rules add the things you notice second: bookshelves, trees, shrubs, lanterns, ribbon windows.</p>
            <p>
              Detailing runs after validation and is re-checked afterwards, so a tree can never cut off a doorway unnoticed. Probabilities come
              from the vegetation and facade-variation parameters.
            </p>
            <RuleCards rules={[...rulesOf("detailing"), ...rulesOf("refine")].slice(0, 5)} />
          </section>

          <section ref={(el) => { sections.current.refine = el; }} data-chapter="refine" className="chapter">
            <h2>Refine</h2>
            <p className="chapter-lede">A Metropolis-Hastings sampler explores small, reversible edits to the massing.</p>
            <p>
              The state is the massing: tower positions and heights, roof gardens, the atrium radius and which candidate bridges exist. A
              proposal picks one move uniformly from fixed lists (shift a tower one cell, add or remove a storey, toggle a garden or bridge,
              widen or narrow the atrium); every move has an equally likely reverse, so the proposal is symmetric. The energy E is a weighted sum
              of stated preferences: connected circulation, daylight, proportion, a preserved void, a strong silhouette, controlled variety,
              greenery and density. A proposal that violates a hard constraint is rejected; otherwise it is accepted with probability
              min(1, e<sup>−ΔE/T</sup>). At a fixed temperature the chain samples massings with probability proportional to e<sup>−E/T</sup>,
              which is why some worse proposals are taken: they let it leave local optima. Lowering T during the run turns it into simulated
              annealing, which is an optimiser and is labelled as such in the studio.
            </p>
            {res && (
              <>
                <dl className="how-stats">
                  <div><dt>Iterations</dt><dd className="num">{res.iterations.toLocaleString()}</dd></div>
                  <div><dt>Accepted</dt><dd className="num">{res.accepted.toLocaleString()}</dd></div>
                  <div><dt>Accepted uphill</dt><dd className="num">{res.acceptedUphill.toLocaleString()}</dd></div>
                  <div><dt>Temperature</dt><dd className="num">{res.temperature}</dd></div>
                  <div><dt>Energy</dt><dd className="num">{res.initial.total.toFixed(2)} to {res.final.total.toFixed(2)}</dd></div>
                  <div><dt>Time</dt><dd className="num">{res.millis} ms</dd></div>
                </dl>
                <EnergyChart trace={res.trace} width={520} height={110} />
                <div className="segmented how-toggle" role="radiogroup" aria-label="Show the design before or after refinement">
                  <button role="radio" aria-checked={!showRefined} className={!showRefined ? "is-on" : ""} onClick={() => setShowRefined(false)}>Before</button>
                  <button role="radio" aria-checked={showRefined} className={showRefined ? "is-on" : ""} onClick={() => setShowRefined(true)}>After</button>
                </div>
              </>
            )}
          </section>

          <section ref={(el) => { sections.current.render = el; }} data-chapter="render" className="chapter">
            <h2>Render</h2>
            <p className="chapter-lede">The grid is semantic. Making it beautiful is a separate step.</p>
            <p>
              A floor cell becomes a thin slab at the bottom of its cell; a window becomes an inset pane with jambs, sill and lintel; walls merge
              into large boxes by greedy meshing; columns and piers merge vertically; terraces and bridges get parapets and railings wherever
              there is a drop. The browser draws each kind and material as one instanced mesh, so the whole library is a few dozen draw calls.
              Clouds, fog, ambient occlusion and a restrained bloom on the lit windows are presentation only. The ink and blueprint modes find
              edges in the depth and normal buffers, so hidden lines are never drawn.
            </p>
            <div className="segmented how-toggle" role="radiogroup" aria-label="Rendering mode">
              {(["diorama", "clay", "ink", "blueprint"] as RenderMode[]).map((m) => (
                <button key={m} role="radio" aria-checked={renderMode === m} className={renderMode === m ? "is-on" : ""} onClick={() => setRenderMode(m)}>
                  {m[0].toUpperCase() + m.slice(1)}
                </button>
              ))}
            </div>
            <Link to="/studio" className="cta how-cta">Open the studio</Link>
          </section>
        </div>

        <div className="how-stage" aria-live="polite">
          <div className="how-stage-inner">
            {gl ? (
              <Viewer
                scene={viewerScene}
                replay={active === "refine" || active === "render" ? null : replay}
                mode={active === "render" ? renderMode : "diorama"}
                quality="medium"
                interactive
                reducedMotion={reduced}
                className="how-viewer"
                label="Replay of the flagship's recorded generation"
              />
            ) : (
              <PlanFallback massing={insp?.massing ?? null} title="the flagship" />
            )}
            <p className="how-stage-caption num">
              {active === "refine" ? (showRefined ? "After refinement" : "Before refinement") : active === "render" ? `Final design, ${renderMode}` : model ? `${model.stageOfFrame(frame)?.label ?? ""}, frame ${frame + 1} of ${model.frameCount}` : "Loading the recorded history…"}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
