# FORMA HTTP API

The engine serves a small JSON API under `/api` (default `http://127.0.0.1:8080`). The web app uses exactly this API; the TypeScript contracts are in `web/src/lib/types.ts`.

* Requests and responses are JSON (UTF-8). Responses over 1 KB are gzip-compressed when the client sends `Accept-Encoding: gzip`.
* Errors are JSON `{"error": "<readable message>"}` with a status code: `400` invalid input (including rule syntax errors, with line numbers), `404` unknown job or resource, `409` result requested before the job completed, `429` queue full, `500` internal error.
* Request bodies are limited to 256 KB. CORS is allowed for `localhost` origins (the Vite dev server).
* Generation is asynchronous: create a job, follow its progress (SSE or polling), then fetch results.
* Limits: 2 worker threads, a queue of 6 waiting jobs (the 7th waiting job gets `429`), 90 s per job, the 24 most recent finished jobs are kept in memory. Refinement accepts 1 to 20,000 iterations.

## Endpoints

### `GET /api/health`
`{"status":"ok","generatorVersion":"forma-engine/0.1.0","activeJobs":0}`

### `GET /api/presets`
All worlds with their parameters, rule program ids, grid size and refinement objectives.

```json
{ "generatorVersion": "forma-engine/0.1.0",
  "presets": [ { "id": "library", "title": "The Library Above the Clouds", "summary": "...", "notes": ["..."],
                 "params": [ { "key": "height", "label": "Atrium tower height", "help": "...", "kind": "int",
                               "min": 8, "max": 22, "step": 1, "default": 16, "group": "massing" } ],
                 "programs": ["growth", "detailing", "refine"], "grid": [72, 63, 72],
                 "objectives": [ { "key": "circulation", "label": "Connected circulation", "description": "..." } ] } ] }
```

### `POST /api/programs`
Body `{"preset": "canal", "params": {"gardens": 0.8}}` returns the default rule program text for those parameters: `{"programs": {"plan": "...", "growth": "...", "detailing": "..."}}`.

### `POST /api/validate-rules`
Body `{"preset": "canal", "overrides": {"growth": "<program text>"}}`. Always `200`: `{"ok": true}` or `{"ok": false, "error": "line 3: rule 'bad': ..."}`.

### `POST /api/jobs/generate`
Body: a generation config, either bare or as `{"config": {...}}`:

```json
{ "preset": "escher", "seed": 4, "params": {"levels": 4}, "ruleOverrides": {"detailing": "<program text>"} }
```

Unknown parameters are rejected; missing ones take their defaults; values are clamped to the declared range. Returns `202` with a job status:

```json
{ "jobId": "gen-1k3x9-12", "type": "generate", "status": "queued", "stage": "queued", "fraction": 0,
  "message": "...", "preset": "escher", "seed": 4, "createdAt": 1764000000000 }
```

`status` moves through `queued`, `running`, then `completed`, `failed` or `cancelled`. Finished jobs add `startedAt`, `finishedAt`, `elapsedMs` and, on failure, `error`.

### `POST /api/jobs/refine`
Runs MCMC refinement on a completed generation (`baseJobId`) or regenerates the base from `config` first.

```json
{ "baseJobId": "gen-1k3x9-12",
  "refine": { "iterations": 2000, "mode": "mcmc", "temperature": 0.25, "temperatureEnd": 0.02,
              "weights": { "daylight": 2.0, "greenery": 0.5 }, "mcmcSeed": 1, "keep": "best" } }
```

`mode` is `mcmc` (fixed temperature) or `anneal` (temperature falls from `temperature` to `temperatureEnd`, which must be positive and not above the start). Weights are clamped to 0 to 5. `keep` is `best` (default: realise the lowest-energy state visited) or `final` (realise the chain's last state).

### `GET /api/jobs/{id}`
The current job status (same shape as above). `DELETE /api/jobs/{id}` asks the job to stop and returns its status; the engine checks for cancellation between and inside stages.

### `GET /api/jobs/{id}/events`
Server-sent events. Events: `status` (sent once on connect), `progress` (throttled), `done` (terminal status, then the stream closes). Each event's data is a job status JSON. Progress events carry an `id`; reconnecting with `Last-Event-ID` replays only what was missed. Comment lines keep the connection alive.

### Results (available once `completed`)

| Endpoint | Content |
|---|---|
| `GET /api/jobs/{id}/scene` | Instanced geometry for the viewer (schema `forma-scene/1`, see below) |
| `GET /api/jobs/{id}/replay` | Recorded history: stages and per-frame sparse cell diffs |
| `GET /api/jobs/{id}/inspector` | Stage notes, rule programs and per-rule application records with before/after samples, node records, constraint checks, the massing, the room graph, cell histogram |
| `GET /api/jobs/{id}/config` | The exact config that reproduces the design (with `generatorVersion`) |
| `GET /api/jobs/{id}/refinement` | Refinement jobs only: energies (initial, final, best, per objective), acceptance statistics, the energy trace, the last accepted moves, checks before and after, both massings, and `kept` |

## Scene format (`forma-scene/1`)

```json
{ "schema": "forma-scene/1", "generatorVersion": "forma-engine/0.1.0", "jobId": "...",
  "config": { ... }, "resolvedParams": { ... }, "presetTitle": "...",
  "grid": { "sx": 72, "sy": 63, "sz": 72, "cellMeters": 1.5, "storeyCells": 2, "groundLevel": 18 },
  "units": "cells", "camera": { "target": [0, 30, 0], "distance": 90, "azimuth": -40, "elevation": 26 },
  "atmosphere": { "sky": "dusk", "cloudy": true, "cloudLevel": 13, "fantasy": false, "projection": "perspective" },
  "bounds": [minX, minY, minZ, maxX, maxY, maxZ],
  "components": [ { "id": 3, "name": "Reading ring 2", "kind": "ring", "material": "sandstone", ... } ],
  "groups": [ { "kind": "box", "material": "sandstone", "count": 812, "data": "<base64 float32>", "comp": "<base64 uint16>" } ],
  "stats": { "instances": 14810, "composeMs": 0, "realizeMs": 67, "sceneMs": 30, "totalMs": 100, "ruleSteps": 911, ... } }
```

Each group is one instanced mesh. `data` holds 9 little-endian float32 values per instance: centre `x, y, z`, size `sx, sy, sz`, yaw `rotY`, `tilt` (rotation about the local x axis after the yaw) and a `variation` value in [0, 1) used for subtle colour variation. `comp` maps each instance to a component id for selection and inspection. Coordinates are in cells (1 cell = 1.5 m), y up, centred on the grid. Kinds: `box`, `cyl`, `tube`, `cone`, `sphere`, `pod`, `torus`, `stair`, `arch`, `foliage`, `pane`, `lamp`.

## Replay format

`frames[i]` has the stage index, a label, `n` changes, `idx` (base64 int32 cell indices, `x + sx*(z + sz*y)`) and `val` (base64 uint8 new cell states, names in `states`). Applying frames in order from an empty grid reproduces the final grid exactly (this is tested). Small consecutive frames are coalesced so that a design has at most 720 frames.
