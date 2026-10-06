/**
 * Colours for semantic cell states, shared by the growth replay voxels and the rule glyphs so
 * that a rule diagram and the 3D replay speak the same visual language.
 * Index = engine cell state id (see engine core/Cell.java).
 */
export const STATE_NAMES = [
  "empty", "air", "wall", "floor", "window", "column", "stair+x", "stair-x", "stair+z", "stair-z",
  "bridge", "terrace", "grass", "vegetation", "water", "terrain", "roof", "glass", "light", "arch",
  "keep-open", "trunk", "core", "support", "shelf", "door", "mark-a", "mark-b", "mark-c", "mark-d",
  "canopy", "path",
] as const;

export const STATE_SYMBOLS = "EAWFNCxXzZBTGVwRrglaKtOPSD1234yp";

export const STATE_COLORS: string[] = [
  "transparent", // empty
  "#24324c",     // air (interior)
  "#e9d5b2",     // wall
  "#b78656",     // floor
  "#ffb547",     // window
  "#f3e6cc",     // column
  "#cbb58f", "#cbb58f", "#cbb58f", "#cbb58f", // stairs
  "#9b6942",     // bridge
  "#d4c19f",     // terrace
  "#6aa84f",     // grass
  "#3f8f4a",     // vegetation
  "#38a9c2",     // water
  "#6c6878",     // terrain
  "#4fb3a0",     // roof (copper patina)
  "#a8dbe8",     // glass
  "#ffd18c",     // light
  "#e2c79c",     // arch
  "#3fe0cf",     // keep-open (reserved void)
  "#6b4a33",     // trunk
  "#8c7a68",     // core
  "#c4b294",     // support
  "#8e4a37",     // shelf
  "#ff8f6b",     // door
  "#ff6f61",     // mark-a (temporary marker)
  "#b98cff",     // mark-b
  "#ffd166",     // mark-c
  "#7fd1ff",     // mark-d
  "#4fb3a0",     // canopy
  "#cdbb9f",     // path
];

/** States drawn in the growth replay (interior air and reserved void are left out). */
export function visibleInReplay(s: number) {
  return s !== 0 && s !== 1 && s !== 20;
}

export function stateName(s: number) {
  return STATE_NAMES[s] ?? `state ${s}`;
}
