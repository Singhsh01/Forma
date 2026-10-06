import { useRef, useState } from "react";
import { DownloadSimple, FileArrowUp, FloppyDisk, Image, Cube, Star, Trash, ArrowSquareIn } from "@phosphor-icons/react";
import { studio, useStudio } from "../../lib/studio";
import { deleteProject, listProjects, parseProjectFile, projectFile, saveProject, storageAvailable, toggleFavorite, type SavedProject } from "../../lib/projects";
import { download, exportGlb, slug } from "../../lib/export";
import type { ViewerApi } from "../../viewer/Viewer";

export function ExportPanel({ viewer }: { viewer: React.RefObject<ViewerApi | null> }) {
  const current = useStudio((s) => s.current);
  const mode = useStudio((s) => s.mode);
  const projection = useStudio((s) => s.projection);
  const busy = useStudio((s) => !!s.job);
  const [projects, setProjects] = useState<SavedProject[]>(() => listProjects());
  const [name, setName] = useState("");
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [working, setWorking] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const canStore = storageAvailable();
  const title = current?.scene.wire.presetTitle ?? "FORMA design";
  const baseName = `${slug(title)}-seed-${current?.config.seed ?? 0}${current?.refinement ? "-refined" : ""}`;

  const refreshList = () => setProjects(listProjects());

  const thumb = async (): Promise<string | undefined> => {
    const c = viewer.current?.canvas();
    if (!c) return undefined;
    try {
      const t = document.createElement("canvas");
      t.width = 160;
      t.height = Math.round((160 * c.height) / Math.max(1, c.width));
      t.getContext("2d")!.drawImage(c, 0, 0, t.width, t.height);
      return t.toDataURL("image/jpeg", 0.72);
    } catch {
      return undefined;
    }
  };

  const onSave = async () => {
    if (!current) return;
    const entry = saveProject({ name: name.trim() || `${title}, seed ${current.config.seed}`, config: current.config, refine: current.refineSettings, favorite: false, thumbnail: await thumb() });
    setMsg(entry ? { ok: true, text: `Saved “${entry.name}” in this browser.` } : { ok: false, text: "This browser blocked local storage, so the project could not be saved. Export the project file instead." });
    setName("");
    refreshList();
  };

  const onExportJson = () => {
    if (!current) return;
    const f = projectFile(`${title}, seed ${current.config.seed}`, current.config, current.refineSettings, { renderMode: mode, projection });
    download(new Blob([JSON.stringify(f, null, 2)], { type: "application/json" }), `${baseName}.forma.json`);
    setMsg({ ok: true, text: "Project file downloaded. Import it here to regenerate the identical design." });
  };

  const onImport = async (file: File) => {
    try {
      const f = parseProjectFile(await file.text());
      setMsg({ ok: true, text: `Loading “${f.name}”: regenerating from its settings${f.refine ? " and re-running its refinement" : ""}.` });
      await studio.loadProject(f.config, f.refine ?? null, f.view ? { renderMode: f.view.renderMode, projection: f.view.projection } : undefined);
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message });
    }
  };

  const onPng = async () => {
    setWorking("png");
    const blob = await viewer.current?.screenshot();
    setWorking(null);
    if (!blob) return setMsg({ ok: false, text: "The viewport could not be captured (WebGL may be unavailable)." });
    download(blob, `${baseName}-${mode}.png`);
    setMsg({ ok: true, text: "PNG downloaded at the viewport’s resolution." });
  };

  const onGlb = async () => {
    const root = viewer.current?.exportRoot();
    if (!root) return setMsg({ ok: false, text: "Nothing to export yet." });
    setWorking("glb");
    try {
      const blob = await exportGlb(root);
      download(blob, `${baseName}.glb`);
      setMsg({ ok: true, text: `GLB downloaded (${(blob.size / 1e6).toFixed(1)} MB, metres, y up). It opens in Blender, three.js editor or any glTF viewer.` });
    } catch (e) {
      setMsg({ ok: false, text: `GLB export failed: ${(e as Error).message}` });
    } finally {
      setWorking(null);
    }
  };

  const favorites = projects.filter((p) => p.favorite);
  const others = projects.filter((p) => !p.favorite);

  return (
    <div className="export">
      <section>
        <h3 className="panel-subheading">Export</h3>
        <div className="export-grid">
          <button className="btn btn-secondary" onClick={onPng} disabled={!current || working !== null} data-testid="export-png"><Image size={15} weight="bold" /> PNG image</button>
          <button className="btn btn-secondary" onClick={onGlb} disabled={!current || working !== null} data-testid="export-glb"><Cube size={15} weight="bold" /> {working === "glb" ? "Baking…" : "GLB model"}</button>
          <button className="btn btn-secondary" onClick={onExportJson} disabled={!current} data-testid="export-json"><DownloadSimple size={15} weight="bold" /> Project file</button>
          <button className="btn btn-secondary" onClick={() => fileRef.current?.click()} disabled={busy} data-testid="import-json"><FileArrowUp size={15} weight="bold" /> Import project</button>
        </div>
        <input ref={fileRef} type="file" accept=".json,application/json" hidden data-testid="import-input" onChange={(e) => { const f = e.target.files?.[0]; if (f) void onImport(f); e.target.value = ""; }} />
        <p className="panel-text">The GLB contains the real scene geometry baked into ordinary meshes. SVG drawings are not offered: the ink and blueprint modes are rendered with screen-space edges, so export them as PNG.</p>
      </section>
      {msg && <p className={"program-status " + (msg.ok ? "is-ok" : "is-error")} role={msg.ok ? "status" : "alert"}>{msg.text}</p>}
      {current && (
        <section>
          <h3 className="panel-subheading">Reproducibility</h3>
          <dl className="meta-list">
            <div><dt>Preset</dt><dd>{current.config.preset}</dd></div>
            <div><dt>Seed</dt><dd className="num">{current.config.seed}</dd></div>
            <div><dt>Generator</dt><dd>{current.scene.wire.generatorVersion}</dd></div>
            <div><dt>Edited rule programs</dt><dd>{Object.keys(current.config.ruleOverrides ?? {}).join(", ") || "none"}</dd></div>
            {current.refineSettings && <div><dt>Refinement</dt><dd className="num">{current.refineSettings.mode}, T {current.refineSettings.temperature}, {current.refineSettings.iterations} steps, sampler seed {current.refineSettings.mcmcSeed}, kept {current.refineSettings.keep === "final" ? "final state" : "lowest energy"}</dd></div>}
            <div><dt>Parameters</dt><dd className="num">{Object.entries(current.config.params).map(([k, v]) => `${k} ${v}`).join(", ")}</dd></div>
          </dl>
        </section>
      )}
      <section>
        <h3 className="panel-subheading">Saved in this browser</h3>
        {canStore ? (
          <form className="save-row" onSubmit={(e) => { e.preventDefault(); void onSave(); }}>
            <label className="visually-hidden" htmlFor="save-name">Project name</label>
            <input id="save-name" className="text-input" placeholder={`${title}, seed ${current?.config.seed ?? ""}`} value={name} onChange={(e) => setName(e.target.value)} />
            <button className="btn btn-secondary" disabled={!current} type="submit"><FloppyDisk size={15} weight="bold" /> Save</button>
          </form>
        ) : (
          <p className="panel-text">Local storage is unavailable in this browser (private mode or blocked site data). Use project files instead.</p>
        )}
        {projects.length === 0 && canStore && <p className="panel-empty">No saved projects yet. Save one to come back to it, or star it as a favourite.</p>}
        {[...favorites, ...others].length > 0 && (
          <ul className="project-list">
            {[...favorites, ...others].map((p) => (
              <li key={p.id} className="project">
                {p.thumbnail ? <img src={p.thumbnail} alt="" width={64} height={40} /> : <span className="project-thumb" />}
                <div className="project-text">
                  <span className="project-name">{p.name}</span>
                  <span className="project-meta num">{p.config.preset}, seed {p.config.seed}{p.refine ? ", refined" : ""}</span>
                </div>
                <button className={"icon-btn" + (p.favorite ? " is-on" : "")} aria-pressed={p.favorite} aria-label={p.favorite ? "Remove from favourites" : "Add to favourites"} onClick={() => { toggleFavorite(p.id); refreshList(); }}>
                  <Star size={15} weight={p.favorite ? "fill" : "bold"} />
                </button>
                <button className="icon-btn" aria-label={`Open ${p.name}`} disabled={busy} onClick={() => void studio.loadProject(p.config, p.refine)}>
                  <ArrowSquareIn size={15} weight="bold" />
                </button>
                <button className="icon-btn" aria-label={`Delete ${p.name}`} onClick={() => { deleteProject(p.id); refreshList(); }}>
                  <Trash size={15} weight="bold" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
