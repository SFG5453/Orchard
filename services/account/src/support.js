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

// Bug reports: filed as issues on GITHUB_REPOSITORY by the service token,
// attributed to the reporter's linked GitHub account.

import { randomToken } from "./crypto.js";
import { githubFetch, githubIdentity, publicGithub, repoPath } from "./github.js";
import { HttpError, json, nowSeconds, requireSession } from "./http.js";
import { CLOSED_FOLLOW_SECONDS, syncQuietly } from "./support_sync.js";

export const REPORT_KINDS = { bug: ["bug"], feature: ["enhancement"], feedback: ["question"] };
export const MAX_TITLE = 140;
export const MAX_BODY = 12000;
export const MAX_DIAGNOSTICS = 16000;
export const MAX_SCREENSHOT_BYTES = 5 * 1024 * 1024;
// [window seconds, reports] per user.
export const REPORT_LIMITS = [[60 * 60, 5], [24 * 60 * 60, 20]];
// Opening the list syncs reports older than this inline; the cron covers the rest.
const LIST_SYNC_AGE = 60;
const LIST_SYNC_MAX = 5;
const SCREENSHOT_PATTERN = /^\/support\/screenshots\/([A-Za-z0-9_-]{32}\.(?:png|jpg|webp))$/;
const SCREENSHOT_TYPES = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp" };

const cleanLine = (value, max) => String(value || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max);
const cleanText = (value, max) => String(value || "").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "").trim().slice(0, max);

function publicEvent(row) {
  return {
    key: row.key,
    kind: row.kind,
    actor: row.actor,
    actor_avatar: row.actor_avatar,
    maintainer: Boolean(row.maintainer),
    reporter: Boolean(row.reporter),
    title: row.title,
    body: row.body,
    url: row.url,
    created_at: row.created_at,
  };
}

function publicReport(row, unread = 0, latest = null) {
  return {
    id: row.id,
    number: row.issue_number,
    url: row.issue_url,
    kind: row.kind,
    title: row.title,
    state: row.state,
    state_reason: row.state_reason,
    created_at: row.created_at,
    updated_at: row.updated_at,
    unread,
    latest: latest ? publicEvent(latest) : null,
  };
}

async function ownedReport(env, userId, reportId) {
  const report = await env.DB.prepare("SELECT * FROM support_reports WHERE id = ? AND user_id = ?").bind(reportId, userId).first();
  if (!report) throw new HttpError(404, "not_found", "No such report");
  return report;
}

function needsSync(report, now) {
  return report.synced_at <= now - LIST_SYNC_AGE &&
    (report.state === "open" || report.updated_at > now - CLOSED_FOLLOW_SECONDS);
}

export async function listReports(request, env) {
  const claims = await requireSession(request, env);
  const now = nowSeconds();
  const stale = await env.DB.prepare("SELECT * FROM support_reports WHERE user_id = ? ORDER BY synced_at ASC").bind(claims.sub).all();
  if (env.GITHUB_TOKEN) {
    await Promise.all(stale.results.filter((report) => needsSync(report, now)).slice(0, LIST_SYNC_MAX)
      .map((report) => syncQuietly(env, report)));
  }

  const [identity, reports, unread, latest] = await Promise.all([
    githubIdentity(env, claims.sub),
    env.DB.prepare("SELECT * FROM support_reports WHERE user_id = ? ORDER BY updated_at DESC LIMIT 100").bind(claims.sub).all(),
    env.DB.prepare(
      `SELECT e.report_id, COUNT(*) AS n FROM support_events e JOIN support_reports r ON r.id = e.report_id
       WHERE r.user_id = ? AND e.notify = 1 AND e.added_at > r.read_at GROUP BY e.report_id`,
    ).bind(claims.sub).all(),
    // Newest notifying event per report, for the list subtitle and in-app alerts.
    env.DB.prepare(
      `SELECT e.* FROM support_events e JOIN support_reports r ON r.id = e.report_id
       WHERE r.user_id = ? AND e.notify = 1 AND e.created_at = (
         SELECT MAX(created_at) FROM support_events WHERE report_id = e.report_id AND notify = 1)`,
    ).bind(claims.sub).all(),
  ]);
  const unreadBy = new Map(unread.results.map((row) => [row.report_id, row.n]));
  const latestBy = new Map(latest.results.map((row) => [row.report_id, row]));
  return json({
    github: publicGithub(identity),
    repository: env.GITHUB_REPOSITORY || "",
    unread: [...unreadBy.values()].reduce((sum, n) => sum + n, 0),
    reports: reports.results.map((row) => publicReport(row, unreadBy.get(row.id) || 0, latestBy.get(row.id))),
  });
}

export async function getReport(request, env, reportId) {
  const claims = await requireSession(request, env);
  const report = await ownedReport(env, claims.sub, reportId);
  const { results } = await env.DB.prepare(
    "SELECT * FROM support_events WHERE report_id = ? ORDER BY created_at ASC, key ASC",
  ).bind(report.id).all();
  const unread = results.filter((event) => event.notify && event.added_at > report.read_at).length;
  return json({ report: publicReport(report, unread, null), events: results.map(publicEvent) });
}

export async function markReportRead(request, env, reportId) {
  const claims = await requireSession(request, env);
  const report = await ownedReport(env, claims.sub, reportId);
  await env.DB.prepare("UPDATE support_reports SET read_at = ? WHERE id = ?").bind(nowSeconds(), report.id).run();
  return new Response(null, { status: 204 });
}

async function enforceReportLimit(env, userId, now) {
  for (const [window, limit] of REPORT_LIMITS) {
    const { results } = await env.DB.prepare(
      "SELECT created_at FROM support_reports WHERE user_id = ? AND created_at > ? ORDER BY created_at DESC LIMIT ?",
    ).bind(userId, now - window, limit).all();
    if (results.length >= limit) {
      const error = new HttpError(429, "rate_limited", "You have sent a lot of reports. Try again later.");
      error.headers = { "retry-after": String(Math.max(1, results[limit - 1].created_at + window - now)) };
      throw error;
    }
  }
}

// Magic bytes must match the declared type; animated images are refused.
export function screenshotExtension(type, bytes) {
  const ascii = new TextDecoder("latin1").decode(bytes.subarray(0, Math.min(bytes.length, 64 * 1024)));
  const png = bytes[0] === 0x89 && ascii.slice(1, 4) === "PNG" && !ascii.includes("acTL");
  const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  const webp = ascii.slice(0, 4) === "RIFF" && ascii.slice(8, 12) === "WEBP" && !ascii.includes("ANIM");
  const ok = { "image/png": png, "image/jpeg": jpeg, "image/webp": webp }[type];
  return ok ? SCREENSHOT_TYPES[type] : null;
}

function diagnosticsBlock(raw) {
  if (!raw) return "";
  let parsed;
  try {
    parsed = JSON.parse(String(raw).slice(0, MAX_DIAGNOSTICS));
  } catch {
    throw new HttpError(400, "invalid_request", "Diagnostics must be JSON");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new HttpError(400, "invalid_request", "Diagnostics must be a JSON object");
  // Escaped backticks keep the fence intact whatever the values hold.
  const text = JSON.stringify(parsed, null, 2).replace(/`/g, "\\u0060");
  return `\n\n<details><summary>Diagnostics</summary>\n\n\`\`\`json\n${text}\n\`\`\`\n</details>`;
}

function issueBody({ body, screenshotUrl, diagnostics, login, platform }) {
  const screenshot = screenshotUrl ? `\n\n![Screenshot](${screenshotUrl})` : "";
  const from = platform ? ` on ${platform}` : "";
  return `${body}${screenshot}${diagnostics}\n\n---\nReported from Orchard${from} by @${login}.`;
}

export async function createReport(request, env) {
  const claims = await requireSession(request, env);
  const identity = await githubIdentity(env, claims.sub);
  if (!identity) throw new HttpError(403, "github_required", "Link your GitHub account to send reports");
  if (!env.GITHUB_TOKEN || !env.GITHUB_REPOSITORY) throw new HttpError(503, "support_unavailable", "Reports are not set up on this server");
  const now = nowSeconds();
  await enforceReportLimit(env, claims.sub, now);

  let form;
  try {
    form = await request.formData();
  } catch {
    throw new HttpError(400, "invalid_request", "Expected multipart form data");
  }
  const kind = String(form.get("kind") || "");
  const title = cleanLine(form.get("title"), MAX_TITLE);
  const body = cleanText(form.get("body"), MAX_BODY);
  if (!REPORT_KINDS[kind]) throw new HttpError(400, "invalid_request", "Choose bug, feature or feedback");
  if (!title || !body) throw new HttpError(400, "invalid_request", "A title and a description are required");
  const diagnostics = diagnosticsBlock(form.get("diagnostics"));

  let screenshotKey = "";
  const file = form.get("screenshot");
  if (file && typeof file === "object" && file.size) {
    if (file.size > MAX_SCREENSHOT_BYTES) throw new HttpError(413, "too_large", "Screenshots must be 5 MiB or smaller");
    const bytes = new Uint8Array(await file.arrayBuffer());
    const extension = screenshotExtension(file.type, bytes);
    if (!extension) throw new HttpError(415, "unsupported_media_type", "Screenshots must be still PNG, JPEG or WebP images");
    screenshotKey = `${randomToken(24)}.${extension}`;
    await env.SUPPORT_FILES.put(`support/${screenshotKey}`, bytes, { httpMetadata: { contentType: file.type } });
  }

  const device = await env.DB.prepare("SELECT platform FROM devices WHERE id = ?").bind(claims.did).first();
  const screenshotUrl = screenshotKey ? new URL(`/support/screenshots/${screenshotKey}`, env.PUBLIC_URL).toString() : "";
  let issue;
  try {
    const response = await githubFetch(env, `${repoPath(env.GITHUB_REPOSITORY)}/issues`, {
      method: "POST",
      body: {
        title,
        body: issueBody({ body, screenshotUrl, diagnostics, login: identity.login, platform: device?.platform }),
        labels: REPORT_KINDS[kind],
      },
    });
    if (!response.ok) throw new Error(`GitHub returned ${response.status}: ${(await response.text()).slice(0, 300)}`);
    issue = await response.json();
    if (!issue.number) throw new Error("GitHub returned no issue number");
  } catch (error) {
    console.error("Support issue creation failed:", error.message);
    if (screenshotKey) await env.SUPPORT_FILES.delete(`support/${screenshotKey}`).catch(() => {});
    throw new HttpError(502, "github_failed", "GitHub did not accept the report. Try again in a moment.");
  }

  const row = {
    id: crypto.randomUUID(), user_id: claims.sub, github_id: identity.subject, repository: env.GITHUB_REPOSITORY,
    issue_number: issue.number, issue_url: String(issue.html_url || `https://github.com/${env.GITHUB_REPOSITORY}/issues/${issue.number}`),
    kind, title, state: "open", state_reason: "", screenshot_key: screenshotKey,
    created_at: now, updated_at: now, read_at: now, synced_at: 0,
  };
  await env.DB.prepare(
    `INSERT INTO support_reports (id, user_id, github_id, repository, issue_number, issue_url, kind, title,
       screenshot_key, created_at, updated_at, read_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(row.id, row.user_id, row.github_id, row.repository, row.issue_number, row.issue_url, row.kind, row.title,
    row.screenshot_key, now, now, now).run();
  return json({ report: publicReport(row) }, 201);
}

export function isScreenshotPath(pathname) {
  return SCREENSHOT_PATTERN.test(pathname);
}

// Public by design: the issue embeds it. The key is 192 random bits.
export async function serveScreenshot(request, env) {
  const key = SCREENSHOT_PATTERN.exec(new URL(request.url).pathname)[1];
  const object = await env.SUPPORT_FILES.get(`support/${key}`);
  if (!object) throw new HttpError(404, "not_found", "No such screenshot");
  const type = Object.entries(SCREENSHOT_TYPES).find(([, extension]) => key.endsWith(`.${extension}`))[0];
  return new Response(object.body, {
    headers: {
      "content-type": type,
      "cache-control": "public, max-age=86400, immutable",
      "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'none'",
    },
  });
}
