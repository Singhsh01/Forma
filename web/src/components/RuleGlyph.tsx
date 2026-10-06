import { useId } from "react";
import { STATE_COLORS, STATE_NAMES, STATE_SYMBOLS } from "../lib/palette";

/**
 * Draws a rewrite rule (or a captured before/after neighbourhood) as small cell diagrams: the
 * signature visual of FORMA. Layers of a 3D pattern are drawn side by side, bottom layer first.
 * Wildcards are open dotted cells; union symbols are hatched.
 */
const UNIONS: Record<string, string> = {
  ".": "empty or interior air",
  _: "any open space",
  "#": "any wall-like mass",
  "^": "any walkable slab",
  i: "interior (air, floor, shelf)",
  o: "exterior or reserved void",
  "*": "anything / unchanged",
};

function cellFill(ch: string): { fill: string; hatch?: boolean; dotted?: boolean; label: string } {
  if (ch === "*") return { fill: "none", dotted: true, label: "anything / unchanged" };
  if (UNIONS[ch]) return { fill: "#8d97ab", hatch: true, label: UNIONS[ch] };
  const s = STATE_SYMBOLS.indexOf(ch);
  if (s < 0) return { fill: "#555", label: ch };
  if (s === 0) return { fill: "rgba(241,232,214,0.05)", label: "empty" };
  return { fill: STATE_COLORS[s], label: STATE_NAMES[s] };
}

function parse(pattern: string): string[][][] {
  // layers (space) -> rows (/) -> chars
  return pattern.trim().split(/ +/).map((layer) => layer.split("/").map((row) => row.split("")));
}

interface GridProps {
  layers: string[][][];
  cell: number;
  title?: string;
}

function Layers({ layers, cell, title }: GridProps) {
  const hatchId = "h" + useId().replace(/[^a-zA-Z0-9]/g, "");
  const nz = layers[0]?.length ?? 1;
  const nx = layers[0]?.[0]?.length ?? 1;
  const gap = Math.max(3, cell * 0.45);
  const w = layers.length * nx * cell + (layers.length - 1) * gap;
  const h = nz * cell;
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} role="img" aria-label={title} className="glyph-grid">
      <defs>
        <pattern id={hatchId} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="4" height="4" fill="rgba(141,151,171,0.25)" />
          <line x1="0" y1="0" x2="0" y2="4" stroke="#aab3c4" strokeWidth="1.4" />
        </pattern>
      </defs>
      {layers.map((rows, li) =>
        rows.map((row, z) =>
          row.map((ch, x) => {
            const f = cellFill(ch);
            const px = li * (nx * cell + gap) + x * cell;
            const py = z * cell;
            return (
              <rect
                key={`${li}-${z}-${x}`}
                x={px + 0.75}
                y={py + 0.75}
                width={cell - 1.5}
                height={cell - 1.5}
                rx={1.5}
                fill={f.hatch ? `url(#${hatchId})` : f.fill}
                stroke={f.dotted ? "rgba(241,232,214,0.45)" : "rgba(10,21,38,0.55)"}
                strokeDasharray={f.dotted ? "2 2" : undefined}
                strokeWidth={1}
              >
                <title>{ch === " " ? "" : `${ch}: ${f.label}`}</title>
              </rect>
            );
          }),
        ),
      )}
    </svg>
  );
}

export function RuleGlyph({ input, output, cell = 12, label }: { input: string; output: string; cell?: number; label?: string }) {
  let a: string[][][], b: string[][][];
  try {
    a = parse(input);
    b = parse(output);
  } catch {
    return <code>{input} -&gt; {output}</code>;
  }
  return (
    <span className="glyph" aria-label={label ?? `rule ${input} becomes ${output}`}>
      <Layers layers={a} cell={cell} title={`input ${input}`} />
      <svg className="glyph-arrow" width={cell * 1.4} height={cell} viewBox="0 0 14 10" aria-hidden>
        <path d="M1 5h10M8 1.5 11.5 5 8 8.5" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <Layers layers={b} cell={cell} title={`output ${output}`} />
    </span>
  );
}

/** Before/after neighbourhood captured from a real application (state ids, layered like the grid). */
export function SampleGlyph({ before, after, nx, ny, nz, cell = 9 }: { before: Uint8Array; after: Uint8Array; nx: number; ny: number; nz: number; cell?: number }) {
  const toLayers = (arr: Uint8Array) =>
    Array.from({ length: ny }, (_, y) =>
      Array.from({ length: nz }, (_, z) => Array.from({ length: nx }, (_, x) => STATE_SYMBOLS[arr[x + nx * (z + nz * y)]] ?? "E")),
    );
  return (
    <span className="glyph glyph-sample" aria-label="captured application: before and after">
      <Layers layers={toLayers(before)} cell={cell} title="before" />
      <svg className="glyph-arrow" width={cell * 1.6} height={cell} viewBox="0 0 14 10" aria-hidden>
        <path d="M1 5h10M8 1.5 11.5 5 8 8.5" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <Layers layers={toLayers(after)} cell={cell} title="after" />
    </span>
  );
}

/** A single legend swatch for a cell symbol. */
export function CellSwatch({ symbol, size = 12 }: { symbol: string; size?: number }) {
  return <Layers layers={[[[symbol]]]} cell={size} title={symbol} />;
}
