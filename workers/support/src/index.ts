import { authenticate, createClient } from './auth';
import { cleanupExpired, enforceRateLimit, pendingDispatches } from './database';
import { deleteDiscordThread, dispatchReport, handleDiscordInteraction } from './discord';
import { empty, errorResponse, HttpError, json, requestIp } from './http';
import {
  createAttributedGithubIssue,
  getClientReport,
  listClientReports,
  readReport,
  removeReport,
  serveAttachment,
  submitReply,
  submitReport
} from './reports';
import type { SupportEnv } from './types';

export default {
  async fetch(request, env, ctx): Promise<Response> {
    if (request.method === 'OPTIONS') return empty(env);
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health' && request.method === 'GET') {
        return json(env, { ok: true, service: 'orchard-support' });
      }
      if (url.pathname === '/v1/github/config' && request.method === 'GET') {
        return json(env, { ok: true, clientId: env.GITHUB_CLIENT_ID || '' });
      }
      if (url.pathname === '/discord/interactions' && request.method === 'POST') {
        return await handleDiscordInteraction(request, env, ctx);
      }
      if (url.pathname === '/v1/clients' && request.method === 'POST') {
        await enforceRateLimit(env, requestIp(request), 'client');
        return json(env, { ok: true, ...(await createClient(env)) }, 201);
      }

      const auth = await authenticate(request, env);
      if (url.pathname === '/v1/reports' && request.method === 'GET') return await listClientReports(env, auth);
      if (url.pathname === '/v1/reports' && request.method === 'POST') return await submitReport(request, env, ctx, auth);
      const attachmentMatch = url.pathname.match(/^\/v1\/reports\/([^/]+)\/attachments\/([^/]+)$/);
      if (attachmentMatch && request.method === 'GET') {
        return await serveAttachment(request, env, auth, attachmentMatch[1], attachmentMatch[2]);
      }
      const messageMatch = url.pathname.match(/^\/v1\/reports\/([^/]+)\/messages$/);
      if (messageMatch && request.method === 'POST') return await submitReply(request, env, ctx, auth, messageMatch[1]);
      const githubMatch = url.pathname.match(/^\/v1\/reports\/([^/]+)\/github$/);
      if (githubMatch && request.method === 'POST') {
        return await createAttributedGithubIssue(request, env, ctx, auth, githubMatch[1]);
      }
      const readMatch = url.pathname.match(/^\/v1\/reports\/([^/]+)\/read$/);
      if (readMatch && request.method === 'POST') return await readReport(env, auth, readMatch[1]);
      const reportMatch = url.pathname.match(/^\/v1\/reports\/([^/]+)$/);
      if (reportMatch && request.method === 'GET') return await getClientReport(env, auth, reportMatch[1]);
      if (reportMatch && request.method === 'DELETE') return await removeReport(env, auth, reportMatch[1]);
      throw new HttpError(404, 'Support endpoint not found.');
    } catch (error) {
      return errorResponse(env, error);
    }
  },

  async scheduled(_controller, env, ctx): Promise<void> {
    ctx.waitUntil(runMaintenance(env));
  }
} satisfies ExportedHandler<SupportEnv>;

async function runMaintenance(env: SupportEnv): Promise<void> {
  for (const report of await pendingDispatches(env)) await dispatchReport(env, report.id);
  for (const report of await cleanupExpired(env)) {
    await deleteDiscordThread(env, report.discord_thread_id);
    const { deleteReportData } = await import('./database');
    await deleteReportData(env, report.id);
  }
}
