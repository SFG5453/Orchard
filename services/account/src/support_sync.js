/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Mirrors GitHub issue activity into support_events so the apps can say who
// replied, which commit mentions the report, and when it closed.

import { encoder } from "./crypto.js";
import { githubFetch, repoPath } from "./github.js";
import { HttpError, nowSeconds } from "./http.js";

const PAGE_SIZE = 100;
const MAX_PAGES_PER_SYNC = 5;
// Commit lookups are extra subrequests; the rest fall back to a short title.
const MAX_COMMIT_LOOKUPS = 5;
const MAX_EVENT_BODY = 4000;
// Activity on closed issues still matters for a while (reopens, late replies).
export const CLOSED_FOLLOW_SECONDS = 30 * 24 * 60 * 60;
// Per cron run; stays under the Workers free-plan subrequest cap.
const SWEEP_BATCH = 20;
const MAINTAINER_ROLES = new Set(["OWNER", "MEMBER", "COLLABORATOR"]);

const seconds = (iso) => Math.floor(Date.parse(iso) / 1000) || nowSeconds();
const shortSha = (sha) => String(sha || "").slice(0, 7);
const clip = (text, length = MAX_EVENT_BODY) => String(text || "").slice(0, length);
const headline = (message) => String(message || "").split("\n")[0].slice(0, 200);

function issueTimelinePath(report, page) {
  return `${repoPath(report.repository)}/issues/${report.issue_number}/timeline?per_page=${PAGE_SIZE}&page=${page}`;
}

async function commitDetails(env, url, budget) {
  // Only GitHub API commit URLs; the timeline hands these to us.
  if (budget.lookups <= 0 || !/^https:\/\/api\.github\.com\/repos\/[^/]+\/[^/]+\/commits\/[0-9a-f]{7,40}$/.test(url || "")) {
    return null;
  }
  budget.lookups -= 1;
  try {
    const response = await githubFetch(env, url);
    if (!response.ok) return null;
    const commit = await response.json();
    return { message: headline(commit.commit?.message), url: String(commit.html_url || "") };
  } catch {
    return null;
  }
}

function closedTitle(item) {
  if (item.commit_id) return `Fixed by commit ${shortSha(item.commit_id)}`;
  if (item.state_reason === "not_planned") return "Closed as not planned";
  if (item.state_reason === "duplicate") return "Closed as a duplicate";
  return "Closed as completed";
}

// One timeline item to one event row, or null for activity nobody needs to hear about.
export async function timelineEvent(env, report, item, budget) {
  const actor = item.actor || item.user || {};
  const base = {
    actor: String(actor.login || ""),
    actor_avatar: String(actor.avatar_url || ""),
    reporter: String(actor.id || "") === report.github_id ? 1 : 0,
    maintainer: 0,
    notify: 1,
    body: "",
    url: report.issue_url,
    created_at: seconds(item.created_at || item.submitted_at),
  };
  switch (item.event) {
    case "commented": {
      const maintainer = MAINTAINER_ROLES.has(item.author_association) ? 1 : 0;
      return {
        ...base, key: `comment:${item.id}`, kind: "comment", maintainer, body: clip(item.body),
        url: String(item.html_url || report.issue_url),
        title: base.reporter ? "You commented" : `${base.actor} ${maintainer ? "replied" : "commented"}`,
      };
    }
    case "referenced": {
      const details = await commitDetails(env, item.commit_url, budget);
      return {
        ...base, key: `commit:${item.commit_id}`, kind: "commit", body: details?.message || "",
        url: details?.url || report.issue_url,
        title: `Commit ${shortSha(item.commit_id)} mentions this report`,
      };
    }
    case "cross-referenced": {
      const source = item.source?.issue;
      if (!source?.number) return null;
      const repo = source.repository?.full_name || report.repository;
      const ref = repo === report.repository ? `#${source.number}` : `${repo}#${source.number}`;
      return {
        ...base, key: `xref:${repo}#${source.number}`, kind: source.pull_request ? "pull_request" : "mention",
        body: clip(source.title, 200), url: String(source.html_url || report.issue_url),
        title: `${source.pull_request ? "Pull request" : "Issue"} ${ref} mentions this report`,
      };
    }
    case "closed":
      return {
        ...base, key: `closed:${item.id}`, kind: "closed", state_reason: item.commit_id ? "completed" : String(item.state_reason || "completed"),
        title: closedTitle(item),
        url: item.commit_id ? `https://github.com/${report.repository}/commit/${item.commit_id}` : report.issue_url,
      };
    case "reopened":
      return { ...base, key: `reopened:${item.id}`, kind: "reopened", title: "Reopened" };
    case "assigned": {
      const assignee = String(item.assignee?.login || base.actor);
      return { ...base, key: `assigned:${item.id}`, kind: "assigned", title: `${assignee} is looking into it` };
    }
    case "labeled":
      // Triage noise: shown in the timeline, never a notification.
      return { ...base, key: `labeled:${item.id}`, kind: "labeled", notify: 0, title: `Labeled ${clip(item.label?.name, 50)}` };
    case "marked_as_duplicate":
      return { ...base, key: `duplicate:${item.id}`, kind: "duplicate", title: "Marked as a duplicate" };
    default:
      return null;
  }
}

async function storeEvents(env, report, events, extra = []) {
  const now = nowSeconds();
  const statements = events.map((event) => env.DB.prepare(
    `INSERT OR IGNORE INTO support_events
       (report_id, key, kind, actor, actor_avatar, maintainer, reporter, notify, title, body, url, created_at, added_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(report.id, event.key, event.kind, event.actor, event.actor_avatar, event.maintainer,
    // Your own activity is history, not news.
    event.reporter, event.reporter ? 0 : event.notify, event.title, event.body, event.url, event.created_at, now));
  const latest = Math.max(0, ...events.map((event) => event.created_at));
  // The newest open/close decides the state; older batches never override it.
  const transitions = events.filter((event) => event.kind === "closed" || event.kind === "reopened")
    .sort((a, b) => a.created_at - b.created_at);
  const last = transitions.at(-1);
  statements.push(env.DB.prepare(
    `UPDATE support_reports SET
       updated_at = MAX(updated_at, ?),
       state = COALESCE(?, state), state_reason = COALESCE(?, state_reason)
     WHERE id = ?`,
  ).bind(latest, last ? (last.kind === "closed" ? "closed" : "open") : null,
    last ? (last.kind === "closed" ? last.state_reason : "") : null, report.id));
  await env.DB.batch([...statements, ...extra]);
}

// Pulls new timeline items for one report. Conditional requests make an
// unchanged issue cost one 304, which GitHub does not count against the quota.
export async function syncReport(env, report) {
  let page = report.timeline_page;
  let etag = report.timeline_etag;
  const items = [];
  for (let fetched = 0; fetched < MAX_PAGES_PER_SYNC; fetched++) {
    const response = await githubFetch(env, issueTimelinePath(report, page), { etag });
    if (response.status === 304) break;
    if (!response.ok) throw new Error(`GitHub timeline returned ${response.status} for #${report.issue_number}`);
    const batch = await response.json();
    items.push(...batch);
    if (batch.length < PAGE_SIZE) {
      etag = response.headers.get("etag") || "";
      break;
    }
    // A full page never changes again; new items land on the next one.
    page += 1;
    etag = "";
  }

  const budget = { lookups: MAX_COMMIT_LOOKUPS };
  const events = [];
  for (const item of items) {
    const event = await timelineEvent(env, report, item, budget);
    if (event) events.push(event);
  }
  await storeEvents(env, report, events, [
    env.DB.prepare("UPDATE support_reports SET synced_at = ?, timeline_page = ?, timeline_etag = ? WHERE id = ?")
      .bind(nowSeconds(), page, etag, report.id),
  ]);
  return events.length;
}

export async function syncQuietly(env, report) {
  try {
    await syncReport(env, report);
  } catch (error) {
    console.error(`Support sync failed for ${report.id}:`, error.message);
  }
}

// Cron: stalest followed reports first, so every report gets a turn.
export async function sweepSupport(env, now = nowSeconds()) {
  if (!env.GITHUB_TOKEN) return;
  const { results } = await env.DB.prepare(
    "SELECT * FROM support_reports WHERE state = 'open' OR updated_at > ? ORDER BY synced_at ASC LIMIT ?",
  ).bind(now - CLOSED_FOLLOW_SECONDS, SWEEP_BATCH).all();
  for (const report of results) await syncQuietly(env, report);
}

async function verifySignature(secret, body, header) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const hex = /^sha256=([0-9a-f]{64})$/.exec(header || "")?.[1];
  if (!hex) return false;
  const signature = new Uint8Array(hex.match(/../g).map((byte) => parseInt(byte, 16)));
  // crypto.subtle.verify compares in constant time.
  return crypto.subtle.verify("HMAC", key, signature, encoder.encode(body));
}

function referencedNumbers(message) {
  // "#12", "fixes #12", "(#12)"; cross-repo "owner/repo#12" is left to the timeline.
  return [...String(message || "").matchAll(/(?:^|[^\w/])#(\d{1,7})\b/g)].map((match) => Number(match[1]));
}

// Optional push channel. The timeline remains the source of truth; this only
// makes changes show up within seconds instead of at the next cron.
export async function githubWebhook(request, env, ctx) {
  if (!env.GITHUB_WEBHOOK_SECRET) throw new HttpError(404, "not_found", "No such route");
  const body = await request.text();
  if (!(await verifySignature(env.GITHUB_WEBHOOK_SECRET, body, request.headers.get("x-hub-signature-256")))) {
    throw new HttpError(401, "invalid_signature", "Webhook signature mismatch");
  }
  const kind = request.headers.get("x-github-event");
  const payload = JSON.parse(body);
  const repository = String(payload.repository?.full_name || "");
  const commits = kind === "push" ? (payload.commits || []).slice(0, 50) : [];
  const numbers = new Set();
  if ((kind === "issues" || kind === "issue_comment") && payload.issue?.number) numbers.add(payload.issue.number);
  for (const commit of commits) referencedNumbers(commit.message).forEach((number) => numbers.add(number));
  if (!repository || numbers.size === 0) return new Response(null, { status: 204 });

  const list = [...numbers].slice(0, 20);
  const { results } = await env.DB.prepare(
    `SELECT * FROM support_reports WHERE repository = ? AND issue_number IN (${list.map(() => "?").join(", ")})`,
  ).bind(repository, ...list).all();

  const work = results.map(async (report) => {
    // Same key as the timeline's referenced event, so the cron later dedupes it.
    const events = commits.filter((commit) => referencedNumbers(commit.message).includes(report.issue_number))
      .map((commit) => ({
        key: `commit:${commit.id}`, kind: "commit", actor: String(commit.author?.username || ""), actor_avatar: "",
        maintainer: 0, reporter: 0, notify: 1, body: headline(commit.message), url: String(commit.url || report.issue_url),
        title: `Commit ${shortSha(commit.id)} mentions this report`, created_at: seconds(commit.timestamp),
      }));
    if (events.length) await storeEvents(env, report, events);
    await syncQuietly(env, report);
  });
  const done = Promise.all(work);
  if (ctx?.waitUntil) ctx.waitUntil(done);
  else await done;
  return new Response(null, { status: 202 });
}
