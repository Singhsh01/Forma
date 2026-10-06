# FORMA design

The product brief is in [PRODUCT.md](PRODUCT.md). This file describes the visual system as built, how it was arrived at, and an honest log of the design refinement passes.

## Direction

The flagship decides the world: a library above the clouds at dusk. The interface borrows from a museum at night: an ink-blue room, ivory wall labels, and warm lamplight as the only colour that asks for attention. The 3D design is always the brightest, most colourful thing on screen; the chrome steps back.

* **Exhibition surfaces** (landing, "How it grows") are editorial: a huge display face, generous space, one authored motion moment (the hero entrance), real renders as plates with museum-style labels.
* **Studio** is a dense tool: borders-only depth, a 1.2 type ratio from 13 px, a 4 px spacing grid, tabular numbers wherever values change.

## Tokens (`web/src/styles/tokens.css`)

Names come from the flagship's world rather than from a template.

| Role | Token | Value | Use |
|---|---|---|---|
| Surfaces | `--night-950` ... `--night-700` | `#0a1526` to `#263f63` | One hue, lightness steps of a few percent |
| Text | `--vellum`, `--vellum-2/3/4` | `#f1e8d6` at 100/76/58/38% | Four levels: primary, supporting, metadata, disabled |
| Lines | `--line`, `--line-2`, `--line-3` | vellum at 9/16/28% | Borders are the only depth cue in the studio |
| Accent | `--lamplight` | `#ffb547` | The single chrome accent: primary action, focus ring, current item |
| Meaning | `--patina`, `--terracotta`, `--moss` | `#4fc3b0`, `#ff7d5e`, `#82c66d` | Selection and circulation, failures, passes. Never decoration |

Radius rule: controls 6 px, containers 10 px, imagery 3 px. Motion: 140 ms and 220 ms with a strong ease-out; everything respects `prefers-reduced-motion` (the hero stops turning, the replay steps instead of animating, transitions are cut).

## Type

* **Syne** (variable, display): wide, architectural, a little strange; used large and tight (negative tracking) for exhibition headlines and the wordmark.
* **Instrument Sans** (variable, UI and text): calm, slightly narrow, excellent at 13 px.
* System monospace for rule programs and numbers in code.

Both fonts are self-hosted through Fontsource (OFL), so the app works offline.

## Signature

**Rule glyphs.** Every rule in the inspector is drawn as a small diagram of its actual input and output cells (layers side by side, symbols as swatches), and each recorded application shows the real before/after cells from the grid. They also appear on the landing page. They exist only because this product has rules; they turn the engine's vocabulary into the interface's.

Supporting signatures: the museum wall label over each design (title, seed, how long the engine took, how many elements), the stage timeline coloured by stage kind (constructive, rules, validation), and the four render modes, each a different reading of the same geometry (diorama for atmosphere, clay for form, ink for drawing, blueprint for construction).

## Render modes

* **Diorama:** physically based materials, per-world sky palette, soft shadows, N8AO ambient occlusion, a restrained bloom on lit windows, clouds for the flagship.
* **Clay:** one warm off-white material, form and shadow only.
* **Ink:** flat paper-white faces plus screen-space edge detection from depth and normals (real edges, not a texture).
* **Blueprint:** flat blue faces with white edges from the same edge pass.

## Accessibility

Keyboard reachable controls with a visible lamplight focus ring; radio groups and tabs with ARIA roles; live regions for job progress and replay position; text alternatives for every image and the 3D view; a 2D plan fallback when WebGL is unavailable; touch targets of at least 40 px on coarse pointers; contrast checked with the Impeccable detector (see the log).

## How the design skills were used

These are the design skills the brief asked for. Each was actually read and used as described; nothing here claims more.

* **frontend-design** (Anthropic skill, read in full): set the process: pick a direction from the product's own world, commit to distinctive type, one dominant colour plus a sharp accent, and an authored motion moment instead of scattered effects. It shaped the night-museum direction, the Syne/Instrument Sans pairing and the single hero entrance.
* **taste-skill** (Leonxlnx/taste-skill, `skills/taste-skill/SKILL.md`, read): the skill targets landing pages, not tool UIs, so it was applied to the landing and "How it grows" pages. Its rules became constraints: the em dash is banned (a final grep removed the last three, across UI, docs and engine strings); eyebrow labels are limited to one per three sections (FORMA uses none); no div-built fake product screenshots (every image of the product is a real render or capture); no invented data.
* **interface-design** (Dammyjay93/interface-design, read): used for the studio as a tool: intent first, token names from the domain, borders-only depth committed to, four text levels, 4 px spacing, tabular numbers, states for every control, and in a late pass the hit-area rule (touch targets enlarged on coarse pointers).
* **impeccable** (pbakaus/impeccable): its CLI detector was run twice. `impeccable detect web/src` (static source scan) returned no findings. `impeccable detect` against the live landing, studio and "How it grows" pages (run with a sandbox-free Chromium because the container runs as root) reported low-contrast text where labels sit over the 3D view; that was fixed (see the log). Impeccable's other commands (`init`, `document`, the live overlay) were not used.

## Refinement log

Each entry is a real change made after looking at screenshots or tool output.

1. **First vertical slice.** The library rendered with too much window glass and a camera that cropped the towers. Windows were narrowed (pane plus jambs, sill, lintel and frame), unlit windows dimmed, and the camera now frames the design's bounding sphere; fog distance follows the camera.
2. **Studio layout.** The rules panel overflowed its column on long programs; the panel intro became a block and flex children got `min-width: 0`. Rule programs scroll inside their own box.
3. **Landing hero.** The headline sits behind a transparent canvas so the library can overlap it. The first version let the tower cover the last letters of "rules." In the final pass the canvas moved right (38vw) and the headline scale was capped at 6.4rem, so the building overlaps only the descenders of "worlds" and the headline reads in full.
4. **Gallery plates.** Placeholder thumbnails were never shipped: every plate and every "How it grows" stage image is captured from the real precomputed designs by `scripts/capture-images.mjs`.
5. **Escher world readability.** The labyrinth only reads as Escher-like in true isometric view, so the world declares an orthographic projection and the studio switches to it when you open that world (you can switch back).
6. **Labels over pale skies.** The studio's wall label was ivory on the paper-coloured Escher sky and in clay/ink modes, which made it unreadable. The label now switches to dark ink when the backdrop behind it is light (computed from the sky palette's luminance and the render mode).
7. **Contrast over 3D.** The Impeccable detector flagged the landing wall label and the studio label as low contrast where they sit over moving geometry (median 1.5:1 to 3:1 on the small lines). Both now sit on a quiet translucent plate (86% ink with a slight blur) and use full-strength vellum for small text; medians are now 5:1 to 12.8:1. The detector still reports a low minimum because it samples individual pixels at glyph edges over bright geometry; the medians are the meaningful figure.
8. **Cancel bug found by the end-to-end tests.** Clicking Cancel swapped the button for the Generate submit button under the pointer, and the same click submitted a new job. Buttons now have distinct keys and Cancel prevents default; the studio is released immediately on cancel and keeps the previous design.
9. **Touch.** Icon buttons were 32 px; on coarse pointers they are now 40 px, as are segmented controls and tabs.
10. **Copy pass.** Removed the last en dashes ("Metropolis-Hastings" is hyphenated), the em dash in the page title, and library-specific wording from the generic refinement panel.

## What would come next

A light theme for printing plates; a proper keyboard shortcut sheet; recording the replay to video; per-world ambient sound was considered and rejected as noise.
