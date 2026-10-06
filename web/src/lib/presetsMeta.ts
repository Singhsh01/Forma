/**
 * Display metadata for worlds, used when the engine is offline (the live list comes from
 * GET /api/presets). Order matches the engine's PresetRegistry.
 */
export const FALLBACK_PRESETS = [
  { id: "library", title: "The Library Above the Clouds", summary: "Terraced reading rooms ring a tall open atrium; linked towers, sky bridges and roof gardens float above a sea of cloud." },
  { id: "cliffside", title: "Cliffside City", summary: "Houses climb a stepped cliff, tied together by terraces, stairs, retaining walls and lookout bridges." },
  { id: "gardens", title: "Hanging Gardens", summary: "Stacked, cantilevered volumes spill planted balconies and water channels around open courtyards." },
  { id: "cathedral", title: "Cathedral of Light", summary: "A symmetric hall of repeated arches and branching columns, with light falling through a dominant nave." },
  { id: "canal", title: "Canal Town", summary: "Rows of houses and small plazas organised around waterways, bridges and pedestrian quays." },
  { id: "organic", title: "Organic Habitat", summary: "Rounded rooms clustered like cells on tree-like supports, joined by branching walkways." },
  { id: "escher", title: "Escher Labyrinth", summary: "Interlocking stairs composed for one viewpoint: an optical illusion, not a traversable building." },
];
