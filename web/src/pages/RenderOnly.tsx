import { useEffect, useState } from "react";
import { Viewer, type Projection } from "../viewer/Viewer";
import { loadStaticRefinedScene, loadStaticReplay, loadStaticScene } from "../lib/staticScenes";
import { ReplayModel } from "../lib/replay";
import { useSearch } from "../lib/router";
import type { RenderMode, Scene } from "../lib/types";

/**
 * Chrome-free render of a precomputed design, used to make genuine gallery thumbnails and the
 * stage images on the "How it grows" page.
 *
 * Query: preset, mode, quality, projection, refined=1 (MCMC-refined design), stage=<stage id>
 * (the recorded voxel state at the end of that stage) or frame=<n>.
 */
export function RenderOnly() {
  const q = useSearch();
  const preset = q.get("preset") ?? "library";
  const mode = (q.get("mode") ?? "diorama") as RenderMode;
  const refined = q.get("refined") === "1";
  const stage = q.get("stage");
  const frameParam = q.get("frame");
  const [scene, setScene] = useState<Scene | null>(null);
  const [replay, setReplay] = useState<{ model: ReplayModel; frame: number } | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const load = refined ? loadStaticRefinedScene(preset).then((s) => s ?? loadStaticScene(preset)) : loadStaticScene(preset);
    load.then(setScene, () => setFailed(true));
    if (stage || frameParam) {
      loadStaticReplay(preset).then((w) => {
        const model = new ReplayModel(w);
        let frame = frameParam ? Number(frameParam) : model.frameCount - 1;
        if (stage) {
          const si = model.stages.findIndex((s) => s.id === stage);
          if (si >= 0) {
            let last = -1;
            for (let f = 0; f < model.frameCount; f++) if (model.frameStage[f] === si) last = f;
            if (last >= 0) frame = last;
          }
        }
        setReplay({ model, frame });
      }, () => setFailed(true));
    }
  }, [preset, refined, stage, frameParam]);

  const projection = (q.get("projection") as Projection | null) ?? (scene?.wire.atmosphere.projection as Projection | undefined) ?? "perspective";
  const ready = scene && (!(stage || frameParam) || replay);
  return (
    <div style={{ position: "fixed", inset: 0 }} data-ready={ready ? "1" : failed ? "error" : "0"}>
      <Viewer scene={scene} mode={mode} quality={(q.get("quality") as "high") ?? "high"} projection={projection} replay={replay}
        className="viewport" interactive={false} reducedMotion />
    </div>
  );
}
