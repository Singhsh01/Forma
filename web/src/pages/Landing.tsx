import { useEffect, useState } from "react";
import { motion, useReducedMotion as useMotionReduced } from "motion/react";
import { ArrowUpRight } from "@phosphor-icons/react";
import { Viewer, webglAvailable } from "../viewer/Viewer";
import { loadStaticInspector, loadStaticScene } from "../lib/staticScenes";
import { FALLBACK_PRESETS } from "../lib/presetsMeta";
import { navigate } from "../lib/router";
import { Link } from "../components/Link";
import { Mark } from "../components/Wordmark";
import { RuleGlyph } from "../components/RuleGlyph";
import type { InspectorWire, Scene } from "../lib/types";
import "../styles/landing.css";

const EASE = [0.23, 1, 0.32, 1] as const;

const STAGES = [
  { verb: "Compose", text: "Seeded code places the masses and keeps the atrium void.", img: "/how/compose.png" },
  { verb: "Grow", text: "Rewrite rules open windows and spread gardens across terraces.", img: "/how/grow.png" },
  { verb: "Check", text: "A walk over the finished grid proves every room is reachable.", img: "/how/check.png" },
  { verb: "Detail", text: "Local rules place bookshelves, trees and lanterns.", img: "/how/detail.png" },
  { verb: "Refine", text: "A Metropolis sampler nudges towers, storeys and bridges.", img: "/how/refine.png" },
  { verb: "Render", text: "Instanced geometry, light and cloud turn cells into a place.", img: "/how/render.png" },
];

const PLATES: Record<string, { size: "wide" | "tall" | "square" }> = {
  cliffside: { size: "wide" },
  gardens: { size: "tall" },
  cathedral: { size: "square" },
  canal: { size: "square" },
  organic: { size: "tall" },
  escher: { size: "wide" },
};

function useAvailableThumbs(ids: string[]) {
  const [ok, setOk] = useState<Record<string, boolean>>({});
  useEffect(() => {
    ids.forEach((id) => {
      const img = new Image();
      img.onload = () => setOk((o) => ({ ...o, [id]: true }));
      img.onerror = () => setOk((o) => ({ ...o, [id]: false }));
      img.src = `/thumbs/${id}-large.png`;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return ok;
}

export function Landing() {
  const reduced = !!useMotionReduced();
  const [scene, setScene] = useState<Scene | null>(null);
  const [insp, setInsp] = useState<InspectorWire | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [gl] = useState(() => webglAvailable());
  const worlds = FALLBACK_PRESETS.filter((p) => p.id !== "library");
  const thumbs = useAvailableThumbs(worlds.map((w) => w.id));

  useEffect(() => {
    loadStaticScene("library").then(setScene, () => setScene(null));
    loadStaticInspector("library").then(setInsp, () => setInsp(null));
  }, []);

  const enter = (e: React.MouseEvent) => {
    e.preventDefault();
    if (reduced) return navigate("/studio");
    setLeaving(true);
    setTimeout(() => navigate("/studio"), 560);
  };

  const growth = insp?.stages.find((s) => s.id === "growth")?.programs[0]?.source;
  const sampleRules = insp?.rules.filter((r) => r.applications > 0 && ["tall", "grow", "party", "shelf", "tree", "merge"].includes(r.rule)).slice(0, 4) ?? [];
  const stats = scene?.wire.stats;

  return (
    <div className={"landing" + (leaving ? " is-leaving" : "")}>
      <header className="site-nav">
        <Link to="/" className="wordmark" aria-label="FORMA home">
          <Mark size={18} />
          <span className="wordmark-text">FORMA</span>
        </Link>
        <nav aria-label="Site">
          <a href="#worlds">Worlds</a>
          <Link to="/how">How it grows</Link>
          <a href="#engine">Engine</a>
          <Link to="/studio" className="nav-studio">Studio</Link>
        </nav>
      </header>

      <section className="hero" aria-labelledby="hero-title">
        <motion.h1
          id="hero-title"
          className="hero-title"
          initial={reduced ? false : { opacity: 0, y: 28 }}
          animate={leaving ? { opacity: 0, y: -40 } : { opacity: 1, y: 0 }}
          transition={{ duration: leaving ? 0.45 : 1.1, ease: EASE }}
        >
          <span>Shape worlds</span>
          <span>with rules.</span>
        </motion.h1>
        {gl && (
          <motion.div
            className="hero-canvas"
            initial={reduced ? false : { opacity: 0, scale: 1.04 }}
            animate={leaving ? { opacity: 0, scale: 1.18 } : { opacity: scene ? 1 : 0, scale: 1 }}
            transition={{ duration: leaving ? 0.55 : 1.6, ease: EASE, delay: leaving ? 0 : 0.15 }}
          >
            <Viewer scene={scene} mode="diorama" quality="medium" transparent autoRotate interactive={false} reducedMotion={reduced} distanceScale={1.2} targetLift={0.12} className="hero-viewer" label="The Library Above the Clouds, slowly turning" />
          </motion.div>
        )}
        <motion.div
          className="hero-copy"
          initial={reduced ? false : { opacity: 0, y: 16 }}
          animate={leaving ? { opacity: 0 } : { opacity: 1, y: 0 }}
          transition={{ duration: 0.9, ease: EASE, delay: leaving ? 0 : 0.5 }}
        >
          <p className="hero-lede">FORMA grows architecture from rewrite rules in Java. Watch a library climb above the clouds, then refine it.</p>
          <div className="hero-actions">
            <a href="/studio" className="cta" onClick={enter} data-testid="enter-studio">Enter the studio</a>
            <Link to="/how" className="cta-quiet">See how it grows</Link>
          </div>
        </motion.div>
        <aside className="wall-label" aria-label="About this exhibit">
          <p className="wall-title">The Library Above the Clouds</p>
          <p>Seed 7, generated by the Java engine{stats ? ` in ${stats.totalMs.toLocaleString()} ms` : ""}.</p>
          <p className="num">{stats ? `${stats.instances.toLocaleString()} elements from ${stats.ruleSteps.toLocaleString()} rule steps` : " "}</p>
        </aside>
      </section>

      <section id="worlds" className="worlds" aria-labelledby="worlds-title">
        <div className="section-head">
          <h2 id="worlds-title">Seven worlds, one engine</h2>
          <p>Each world is its own composition with its own rules, sharing the same Java machinery. Every image here is a render of geometry the engine produced, with the seed that reproduces it.</p>
        </div>
        <div className="plates">
          {worlds.map((w) => {
            const has = thumbs[w.id];
            const size = PLATES[w.id]?.size ?? "square";
            return (
              <article key={w.id} className={`plate plate-${size}${has === false ? " is-missing" : ""}`}>
                <Link to={`/studio?preset=${w.id}`} className="plate-link" aria-label={`Open ${w.title} in the studio`}>
                  <div className="plate-frame">
                    {has !== false && <img src={`/thumbs/${w.id}-large.png`} alt={`${w.title}, rendered from generated geometry`} loading="lazy" />}
                    {has === false && <span className="plate-pending">Render pending</span>}
                  </div>
                  <div className="plate-label">
                    <h3>{w.title}</h3>
                    <p>{w.summary}</p>
                    <span className="plate-open">Open in studio <ArrowUpRight size={14} weight="bold" aria-hidden /></span>
                  </div>
                </Link>
              </article>
            );
          })}
        </div>
      </section>

      <section className="grows" aria-labelledby="grows-title">
        <div className="section-head">
          <h2 id="grows-title">How a world grows</h2>
          <p>Generation is recorded as it happens. These frames come from the flagship's own history, from first rock to final render.</p>
        </div>
        <ol className="grow-strip">
          {STAGES.map((s) => (
            <li key={s.verb}>
              <div className="grow-frame">
                <img src={s.img} alt={`${s.verb}: frame from the recorded generation`} loading="lazy" onError={(e) => ((e.target as HTMLImageElement).style.opacity = "0")} />
              </div>
              <h3>{s.verb}</h3>
              <p>{s.text}</p>
            </li>
          ))}
        </ol>
        <Link to="/how" className="cta-quiet grows-more">Walk through every stage</Link>
      </section>

      <section id="engine" className="engine" aria-labelledby="engine-title">
        <div className="engine-text">
          <h2 id="engine-title">An engine you can read</h2>
          <p>
            Underneath is a small Java library with no dependencies. Rules rewrite patterns of cells: <em>one</em> nodes apply a random match,
            <em> all</em> nodes apply every non-overlapping match, and <em>sequence</em> and <em>markov</em> nodes order them. Wave Function
            Collapse fills tile lattices under explicit adjacency constraints, a graph walk proves circulation, and a Metropolis-Hastings sampler
            explores refinements against stated design objectives.
          </p>
          <p>
            The rule language follows ideas from Maxim Gumin's{" "}
            <a href="https://github.com/mxgmn/MarkovJunior" target="_blank" rel="noreferrer">MarkovJunior</a>: ordered rewrite rules, seeded
            random matches, symmetry and constraint propagation. FORMA is an original implementation of a documented subset, not a port, and its
            refinement sampler is its own; it does not reproduce{" "}
            <a href="https://github.com/mxgmn/ConvChain" target="_blank" rel="noreferrer">ConvChain</a>.
          </p>
          {sampleRules.length > 0 && (
            <ul className="engine-rules">
              {sampleRules.map((r) => (
                <li key={r.rule}>
                  <RuleGlyph input={r.input} output={r.output} cell={13} />
                  <span>
                    <code>{r.rule}</code> fired {r.applications.toLocaleString()} times in the flagship
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <figure className="engine-code">
          <figcaption>The growth program that ran for the flagship above, exactly as executed.</figcaption>
          <pre>{growth ?? "Loading…"}</pre>
        </figure>
      </section>

      <footer className="site-footer">
        <div>
          <Mark size={16} />
          <p>FORMA, Worlds by Rules. Generation in Java, rendering in the browser, all running on your machine.</p>
        </div>
        <nav aria-label="Footer">
          <Link to="/studio">Studio</Link>
          <Link to="/how">How it grows</Link>
          <a href="https://github.com/mxgmn/MarkovJunior" target="_blank" rel="noreferrer">MarkovJunior (MIT)</a>
        </nav>
      </footer>
      <div className="leave-veil" aria-hidden />
    </div>
  );
}
