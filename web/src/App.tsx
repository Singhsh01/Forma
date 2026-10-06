import { lazy, Suspense } from "react";
import { usePath } from "./lib/router";
import { Studio } from "./pages/Studio";

const Landing = lazy(() => import("./pages/Landing").then((m) => ({ default: m.Landing })));
const HowItGrows = lazy(() => import("./pages/HowItGrows").then((m) => ({ default: m.HowItGrows })));
const RenderOnly = lazy(() => import("./pages/RenderOnly").then((m) => ({ default: m.RenderOnly })));

export function App() {
  const path = usePath();
  let page;
  if (path.startsWith("/studio")) page = <Studio />;
  else if (path.startsWith("/how")) page = <HowItGrows />;
  else if (path.startsWith("/render")) page = <RenderOnly />;
  else if (path === "/" || path === "") page = <Landing />;
  else page = <NotFound />;
  return <Suspense fallback={<div className="page-loading" aria-busy="true" />}>{page}</Suspense>;
}

function NotFound() {
  return (
    <main className="not-found">
      <h1>No such room</h1>
      <p>This page does not exist. <a href="/">Return to the exhibition</a> or <a href="/studio">open the studio</a>.</p>
    </main>
  );
}
