import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/lib/api";

afterEach(() => vi.unstubAllGlobals());

function respond(status: number, body: string) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(body, { status })));
}

describe("API client errors", () => {
  it("surfaces the engine's own error message and status", async () => {
    respond(400, JSON.stringify({ error: "unknown preset 'atlantis'" }));
    const e = await api.presets().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(400);
    expect(e.message).toContain("atlantis");
  });

  it("explains a full queue", async () => {
    respond(429, JSON.stringify({ error: "the generator queue is full; try again shortly" }));
    const e = await api.generate({ preset: "library", seed: 1, params: {}, ruleOverrides: {} }).catch((x) => x);
    expect(e.status).toBe(429);
    expect(e.message).toMatch(/queue is full/);
  });

  it("says plainly when the engine cannot be reached", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("fetch failed"); }));
    const e = await api.health().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(0);
    expect(e.message).toMatch(/Can't reach the FORMA engine/);
  });

  it("rejects a non-JSON response", async () => {
    respond(200, "<html>proxy page</html>");
    await expect(api.health()).rejects.toThrow(/not JSON/);
  });
});
