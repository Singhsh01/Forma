import { useSyncExternalStore } from "react";

/** A tiny History-API router: three routes do not justify a routing dependency. */
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

if (typeof window !== "undefined") window.addEventListener("popstate", emit);

export function navigate(to: string, opts: { replace?: boolean } = {}) {
  if (to === location.pathname + location.search) return;
  if (opts.replace) history.replaceState(null, "", to);
  else history.pushState(null, "", to);
  window.scrollTo({ top: 0 });
  emit();
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function usePath(): string {
  return useSyncExternalStore(subscribe, () => location.pathname, () => "/");
}

export function useSearch(): URLSearchParams {
  const s = useSyncExternalStore(subscribe, () => location.search, () => "");
  return new URLSearchParams(s);
}
