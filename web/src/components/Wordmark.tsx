import { Link } from "./Link";

/**
 * FORMA wordmark. The mark is a real rule from the flagship's growth program: a stacked window bay
 * ("1 1") rewritten into a tall lit window ("N N"), drawn as two 1x2 cell columns.
 */
export function Mark({ size = 18 }: { size?: number }) {
  const c = size / 2;
  return (
    <svg width={size * 1.7} height={size} viewBox={`0 0 ${size * 1.7} ${size}`} aria-hidden className="mark">
      <rect x={0.5} y={0.5} width={c - 1} height={c - 1} rx={1} fill="#ff6f61" />
      <rect x={0.5} y={c + 0.5} width={c - 1} height={c - 1} rx={1} fill="#ff6f61" />
      <path d={`M${c + 2} ${c} h${size * 0.3}`} stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" opacity="0.6" />
      <rect x={size * 1.7 - c + 0.5} y={0.5} width={c - 1} height={c - 1} rx={1} fill="#ffb547" />
      <rect x={size * 1.7 - c + 0.5} y={c + 0.5} width={c - 1} height={c - 1} rx={1} fill="#ffb547" />
    </svg>
  );
}

export function Wordmark({ to = "/", compact = false }: { to?: string; compact?: boolean }) {
  return (
    <Link to={to} className="wordmark" aria-label="FORMA home">
      <Mark size={compact ? 14 : 18} />
      <span className="wordmark-text">FORMA</span>
    </Link>
  );
}
