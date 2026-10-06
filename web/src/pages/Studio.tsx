import { useEffect, useMemo, useRef, useState } from "react";
import { X } from "@phosphor-icons/react";
import { backdropIsLight, Viewer, webglAvailable, type ViewerApi } from "../viewer/Viewer";
import { studio, useStudio, type Tab } from "../lib/studio";
import { useSearch } from "../lib/router";
import { TopBar } from "../components/studio/TopBar";
import { PresetRail } from "../components/studio/PresetRail";
import { ParamPanel } from "../components/studio/ParamPanel";
import { RulesPanel } from "../components/studio/RulesPanel";
import { ChecksPanel } from "../components/studio/ChecksPanel";
import { RefinePanel } from "../components/studio/RefinePanel";
import { ExportPanel } from "../components/studio/ExportPanel";
import { ViewToolbar } from "../components/studio/ViewToolbar";
import { Timeline } from "../components/studio/Timeline";
import { JobOverlay } from "../components/studio/JobOverlay";
import { PlanFallback } from "../components/studio/PlanFallback";
import { useReducedMotion } from "../lib/useReducedMotion";

const TABS: { id: Tab; label: string }[] = [
  { id: "params", label: "Parameters" },
  { id: "rules", label: "Rules" },
  { id: "checks", label: "Checks" },
  { id: "refine", label: "Refine" },
  { id: "export", label: "Export" },
];

function ExhibitLabel() {
  const current = useStudio((s) => s.current);
  const compare = useStudio((s) => s.compare);
  const showBefore = useStudio((s) => s.compareShowBefore);
  const mode = useStudio((s) => s.mode);
  const replayActive = useStudio((s) => s.replay.active);
  if (!current) return null;
  const w = current.scene.wire;
  // the replay is drawn over the same sky, so the label follows the sky and render mode either way
  const light = backdropIsLight(current.scene, replayActive ? "diorama" : mode);
  return (
    <div className={"exhibit-label" + (light ? " on-light" : "")}>
      <h1>{w.presetTitle}</h1>
      <p className="num">
        Seed {w.config.seed}
        {current.source === "static" ? ", precomputed" : ""}
        {current.refinement ? (compare && showBefore ? ", before refinement" : ", refined") : ""}
      </p>
      <p className="exhibit-stats num">
        {w.stats.instances.toLocaleString()} elements, generated in {w.stats.totalMs.toLocaleString()} ms
      </p>
    </div>
  );
}

function SelectionCard() {
  const sel = useStudio((s) => s.selected);
  const current = useStudio((s) => s.current);
  if (sel === null || !current) return null;
  const c = current.scene.wire.components.find((x) => x.id === sel);
  if (!c) return null;
  return (
    <div className="selection-card" role="status">
      <div>
        <p className="selection-name">{c.name}</p>
        <p className="selection-meta">{c.kind}, {c.material}{c.floating ? ", floating (fantasy)" : ""}</p>
      </div>
      <button className="icon-btn" onClick={() => studio.select(null)} aria-label="Clear selection"><X size={14} weight="bold" /></button>
    </div>
  );
}

function Toasts() {
  const error = useStudio((s) => s.error);
  const notice = useStudio((s) => s.notice);
  return (
    <div className="toasts">
      {error && (
        <div className="toast toast-error" role="alert" data-testid="error-toast">
          <p>{error}</p>
          <button className="icon-btn" onClick={() => studio.dismissError()} aria-label="Dismiss"><X size={14} weight="bold" /></button>
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          <p>{notice}</p>
          <button className="icon-btn" onClick={() => studio.dismissNotice()} aria-label="Dismiss"><X size={14} weight="bold" /></button>
        </div>
      )}
    </div>
  );
}

export function Studio() {
  const search = useSearch();
  const viewer = useRef<ViewerApi>(null);
  const reduced = useReducedMotion();
  const [gl] = useState(() => webglAvailable());
  const current = useStudio((s) => s.current);
  const previous = useStudio((s) => s.previous);
  const compare = useStudio((s) => s.compare);
  const showBefore = useStudio((s) => s.compareShowBefore);
  const mode = useStudio((s) => s.mode);
  const projection = useStudio((s) => s.projection);
  const quality = useStudio((s) => s.quality);
  const cinematic = useStudio((s) => s.cinematic);
  const clouds = useStudio((s) => s.clouds);
  const selected = useStudio((s) => s.selected);
  const tab = useStudio((s) => s.tab);
  const replay = useStudio((s) => s.replay);
  const loadingInitial = useStudio((s) => s.loadingInitial);
  const inspector = useStudio((s) => s.inspector);

  useEffect(() => {
    if (!studio.get().current) {
      const seed = search.get("seed");
      void studio.init(search.get("preset"), seed ? Number(seed) : null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // keyboard: B toggles before/after while comparing, space plays the replay
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT")) return;
      if (e.key === "b" && studio.get().compare) studio.setView({ compareShowBefore: !studio.get().compareShowBefore });
      if (e.key === " " && studio.get().replay.active) {
        e.preventDefault();
        studio.get().replay.playing ? studio.pause() : studio.play();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => () => studio.pause(), []);

  const shown = compare && showBefore && previous ? previous.scene : current?.scene ?? null;
  const replayProp = useMemo(() => (replay.active && replay.model ? { model: replay.model, frame: replay.frame } : null), [replay.active, replay.model, replay.frame]);

  return (
    <div className="studio">
      <TopBar />
      <PresetRail />
      <main className="stage-area" aria-label="Design viewport" data-testid="stage"
        data-preset={current?.config.preset ?? ""} data-seed={current?.config.seed ?? ""} data-source={current?.source ?? ""}
        data-job={current?.jobId ?? ""} data-instances={current?.scene.wire.stats.instances ?? 0} data-refined={current?.refinement ? "1" : "0"}
        data-showing={shown === current?.scene ? "current" : shown ? "before" : "none"}>
        {gl ? (
          <Viewer
            ref={viewer}
            className="viewport"
            scene={shown}
            mode={mode}
            projection={projection}
            quality={quality}
            cinematic={cinematic}
            clouds={clouds}
            selected={selected}
            onSelect={(c) => studio.select(c)}
            replay={replayProp}
            reducedMotion={reduced}
            label={current ? `3D view of ${current.scene.wire.presetTitle}, seed ${current.config.seed}` : "3D view"}
          />
        ) : (
          <PlanFallback massing={inspector.data?.massing ?? null} title={current?.scene.wire.presetTitle ?? "design"} />
        )}
        {loadingInitial && !current && <div className="viewport-loading" aria-busy="true">Loading the flagship design…</div>}
        <ExhibitLabel />
        <ViewToolbar onReset={() => viewer.current?.resetCamera()} />
        {compare && previous && (
          <div className="compare-switch segmented" role="radiogroup" aria-label="Before or after refinement">
            <button role="radio" aria-checked={showBefore} className={showBefore ? "is-on" : ""} onClick={() => studio.setView({ compareShowBefore: true })} data-testid="compare-before">Before</button>
            <button role="radio" aria-checked={!showBefore} className={!showBefore ? "is-on" : ""} onClick={() => studio.setView({ compareShowBefore: false })} data-testid="compare-after">After</button>
          </div>
        )}
        <SelectionCard />
        <JobOverlay />
        <Timeline />
        <Toasts />
      </main>
      <aside className="inspector" aria-label="Design controls">
        <div className="tabs" role="tablist" aria-label="Studio panels">
          {TABS.map((t) => (
            <button key={t.id} role="tab" id={`tab-${t.id}`} aria-selected={tab === t.id} aria-controls={`panel-${t.id}`} className={tab === t.id ? "is-on" : ""} onClick={() => studio.setTab(t.id)} data-testid={`tab-${t.id}`}>
              {t.label}
            </button>
          ))}
        </div>
        <div className="tab-panel" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
          {tab === "params" && <ParamPanel />}
          {tab === "rules" && <RulesPanel />}
          {tab === "checks" && <ChecksPanel />}
          {tab === "refine" && <RefinePanel />}
          {tab === "export" && <ExportPanel viewer={viewer} />}
        </div>
      </aside>
    </div>
  );
}
