import assert from "node:assert/strict";
import { setImmediate } from "node:timers/promises";
import { test } from "node:test";
import { createContext, runInContext } from "node:vm";
import { html, script } from "../src/ui.ts";

type Candidate = { key: string; version: string; size: number };
type Call = { path: string; options?: RequestInit };

class Element {
  disabled = false;
  textContent = "";
  className = "";
  listeners = new Map<string, () => unknown>();
  append() {}
  replaceChildren() {}
  querySelectorAll() { return []; }
  addEventListener(event: string, listener: () => unknown) { this.listeners.set(event, listener); }
}

async function consoleFixture(
  respond: (call: Call) => { status?: number; data: unknown },
  confirmed = true,
) {
  const elements = new Map<string, Element>();
  for (const [, id] of html.matchAll(/id="([^"]+)"/g)) elements.set(id, new Element());
  const calls: Call[] = [];
  const confirmations: string[] = [];
  const context = createContext({
    document: {
      getElementById: (id: string) => elements.get(id),
      createElement: () => new Element(),
    },
    fetch: async (path: string, options?: RequestInit) => {
      if (path === "/api/manifests") {
        return { ok: true, json: async () => ({ channels: [], manifests: [], minAgeDays: 7 }) };
      }
      assert.equal(elements.get("clean-all-unused")!.disabled, true);
      assert.equal(elements.get("scan")!.disabled, true);
      const call = { path, options };
      calls.push(call);
      const result = respond(call);
      return { ok: (result.status ?? 200) < 400, json: async () => result.data };
    },
    confirmCleanup: async (description: string) => {
      confirmations.push(description);
      return confirmed;
    },
  });
  runInContext(script, context);
  await setImmediate();
  runInContext("confirmDelete = confirmCleanup", context);
  return {
    elements, calls, confirmations,
    clean: () => elements.get("clean-all-unused")!.listeners.get("click")!(),
  };
}

function candidates(count: number, start = 0): Candidate[] {
  return Array.from({ length: count }, (_, i) => ({
    key: `objects/${(start + i).toString(16).padStart(64, "0")}`, version: `v${start + i}`, size: 1024,
  }));
}

function deletion(call: Call): Candidate[] {
  assert.equal(call.path, "/api/delete-objects");
  assert.equal(call.options!.method, "POST");
  assert.deepEqual({ ...call.options!.headers }, { "Content-Type": "application/json", "X-Orchard-Admin": "1" });
  const body = JSON.parse(call.options!.body as string);
  assert.equal(body.confirm, "DELETE");
  assert.ok(body.objects.length > 0 && body.objects.length <= 100);
  assert.ok(body.objects.every((entry: Candidate) => Object.keys(entry).sort().join(",") === "key,version"));
  return body.objects;
}

test("bulk cleanup traverses empty pages and deletes candidates in checked batches", async () => {
  const first = candidates(205), last = candidates(1, 205);
  const fixture = await consoleFixture((call) => {
    if (call.path === "/api/orphans") return { data: { candidates: first, cursor: "page 2/+" } };
    if (call.path === "/api/orphans?cursor=page%202%2F%2B") return { data: { candidates: [], cursor: "last" } };
    if (call.path === "/api/orphans?cursor=last") return { data: { candidates: last, cursor: null } };
    return { data: { deleted: deletion(call).map((entry) => entry.key) } };
  });
  await fixture.clean();
  assert.equal(fixture.confirmations.length, 1);
  assert.match(fixture.confirmations[0], /Retained manifests/);
  const batches = fixture.calls.filter((call) => call.options).map(deletion);
  assert.deepEqual(batches.map((batch) => batch.length), [100, 100, 5, 1]);
  assert.deepEqual(batches.flat(), [...first, ...last].map(({ key, version }) => ({ key, version })));
  assert.match(fixture.elements.get("status")!.textContent, /Cleanup complete.*206 unused object\(s\), 206.0 KiB/);
  assert.match(fixture.elements.get("object-info")!.textContent, /3 page\(s\) scanned/);
  assert.equal(fixture.elements.get("clean-all-unused")!.disabled, false);
});

test("canceling bulk cleanup makes no scan or delete requests", async () => {
  const fixture = await consoleFixture(() => { throw Error("Unexpected request"); }, false);
  await fixture.clean();
  assert.equal(fixture.calls.length, 0);
  assert.equal(fixture.elements.get("clean-all-unused")!.disabled, false);
});

test("a conflict stops cleanup and reports completed deletions", async () => {
  let batches = 0;
  const fixture = await consoleFixture((call) => {
    if (call.path === "/api/orphans") return { data: { candidates: candidates(205), cursor: "later" } };
    const entries = deletion(call);
    if (++batches === 2) return { status: 409, data: { error: "Release metadata changed during scan" } };
    return { data: { deleted: entries.map((entry) => entry.key) } };
  });
  await fixture.clean();
  assert.equal(fixture.calls.length, 3);
  assert.match(fixture.elements.get("status")!.textContent, /stopped after deleting 100 object\(s\).*Release metadata changed/);
  assert.equal(fixture.elements.get("status")!.className, "error");
  assert.match(fixture.elements.get("object-info")!.textContent, /100.0 KiB freed/);
  assert.equal(fixture.elements.get("clean-all-unused")!.disabled, false);
});

test("scan failures stop cleanup without deleting anything", async () => {
  const fixture = await consoleFixture(() => ({ status: 400, data: { error: "No release channels found" } }));
  await fixture.clean();
  assert.equal(fixture.calls.length, 1);
  assert.match(fixture.elements.get("status")!.textContent, /stopped after deleting 0 object\(s\).*No release channels/);
  assert.equal(fixture.elements.get("clean-all-unused")!.disabled, false);
});

test("an empty bucket finishes without delete requests", async () => {
  const fixture = await consoleFixture(() => ({ data: { candidates: [], cursor: null } }));
  await fixture.clean();
  assert.equal(fixture.calls.length, 1);
  assert.match(fixture.elements.get("status")!.textContent, /Cleanup complete.*0 unused object\(s\)/);
});
