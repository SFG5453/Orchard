import {
  IMAGE_TYPES,
  MAX_BODY_LENGTH,
  MAX_SCREENSHOT_BYTES,
  REPORT_TYPES
} from './constants';
import {
  addAttachment,
  clearGithubUserPending,
  createMessage,
  createReport,
  deleteReportData,
  enforceRateLimit,
  getAttachment,
  getOwnedReport,
  listAttachments,
  listMessages,
  listReports,
  markRead
} from './database';
import { deleteDiscordThread, dispatchReport, postReportMessage } from './discord';
import { createGithubIssue } from './github';
import { cleanDiagnostics, cleanText, cleanTitle, corsHeaders, HttpError, json } from './http';
import type { AuthenticatedClient, ReportRow, ReportType, SupportEnv } from './types';

export async function listClientReports(env: SupportEnv, auth: AuthenticatedClient): Promise<Response> {
  const reports = await listReports(env, auth.client.id);
  const summaries = await Promise.all(reports.map(async (report) => {
    const messages = await listMessages(env, report.id);
    const latest = messages.at(-1) || null;
    return {
      ...publicReport(report),
      unread: Boolean(latest?.sender === 'staff' && (!report.user_read_at || latest.created_at > report.user_read_at)),
      latestMessage: latest ? publicMessage(latest) : null
    };
  }));
  return json(env, {
    ok: true,
    reports: summaries
  });
}

export async function getClientReport(
  env: SupportEnv,
  auth: AuthenticatedClient,
  reportId: string
): Promise<Response> {
  const report = await getOwnedReport(env, reportId, auth.client.id);
  const [messages, attachments] = await Promise.all([
    listMessages(env, report.id),
    listAttachments(env, report.id)
  ]);
  return json(env, {
    ok: true,
    report: publicReport(report),
    messages: messages.map(publicMessage),
    attachments: attachments.map((attachment) => ({
      id: attachment.id,
      messageId: attachment.message_id,
      filename: attachment.filename,
      contentType: attachment.content_type,
      byteSize: attachment.byte_size,
      url: `/v1/reports/${report.id}/attachments/${attachment.id}`
    }))
  });
}

export async function submitReport(
  request: Request,
  env: SupportEnv,
  ctx: ExecutionContext,
  auth: AuthenticatedClient
): Promise<Response> {
  await enforceRateLimit(env, auth.client.id, 'report');
  const input = await parseSubmission(request);
  if (!REPORT_TYPES.has(input.type as ReportType)) throw new HttpError(400, 'Choose a valid report type.');
  const title = cleanTitle(input.title);
  const body = cleanText(input.body);
  if (!title || !body) throw new HttpError(400, 'A title and description are required.');
  const screenshot = input.screenshot ? await validateScreenshot(input.screenshot) : null;

  const created = await createReport(env, {
    clientId: auth.client.id,
    type: input.type as ReportType,
    title,
    body,
    diagnostics: cleanDiagnostics(input.diagnostics),
    githubUserPending: input.githubUserPending
  });
  if (screenshot) await addAttachment(env, created.report.id, created.message.id, screenshot);
  ctx.waitUntil(dispatchReport(env, created.report.id));
  return getClientReport(env, auth, created.report.id);
}

export async function createAttributedGithubIssue(
  request: Request,
  env: SupportEnv,
  ctx: ExecutionContext,
  auth: AuthenticatedClient,
  reportId: string
): Promise<Response> {
  const report = await getOwnedReport(env, reportId, auth.client.id);
  const token = request.headers.get('x-github-user-token') || '';
  if (!/^[A-Za-z0-9_]{20,512}$/.test(token)) throw new HttpError(400, 'A valid GitHub user token is required.');
  if (await createGithubIssue(env, report.id, { token, force: true })) {
    return getClientReport(env, auth, report.id);
  }
  await clearGithubUserPending(env, report.id);
  ctx.waitUntil(createGithubIssue(env, report.id));
  throw new HttpError(502, 'The report was sent, but GitHub attribution failed. Orchard will retry without attribution.');
}

export async function submitReply(
  request: Request,
  env: SupportEnv,
  ctx: ExecutionContext,
  auth: AuthenticatedClient,
  reportId: string
): Promise<Response> {
  await enforceRateLimit(env, auth.client.id, 'reply');
  const report = await getOwnedReport(env, reportId, auth.client.id);
  if (!['open', 'waiting_on_user'].includes(report.status)) throw new HttpError(409, 'This report is closed.');
  const input = await parseSubmission(request);
  const body = cleanText(input.body);
  if (!body) throw new HttpError(400, 'A reply is required.');
  const screenshot = input.screenshot ? await validateScreenshot(input.screenshot) : null;
  const message = await createMessage(env, report.id, 'user', body);
  if (screenshot) await addAttachment(env, report.id, message.id, screenshot);
  ctx.waitUntil(postReportMessage(env, report, body, message.id));
  return getClientReport(env, auth, report.id);
}

export async function readReport(
  env: SupportEnv,
  auth: AuthenticatedClient,
  reportId: string
): Promise<Response> {
  await getOwnedReport(env, reportId, auth.client.id);
  await markRead(env, reportId);
  return json(env, { ok: true });
}

export async function removeReport(
  env: SupportEnv,
  auth: AuthenticatedClient,
  reportId: string
): Promise<Response> {
  const report = await getOwnedReport(env, reportId, auth.client.id);
  await deleteDiscordThread(env, report.discord_thread_id, true);
  await deleteReportData(env, report.id);
  console.log(JSON.stringify({ message: 'support report deleted by user', reportId: report.id }));
  return json(env, { ok: true });
}

export async function serveAttachment(
  request: Request,
  env: SupportEnv,
  auth: AuthenticatedClient,
  reportId: string,
  attachmentId: string
): Promise<Response> {
  await enforceRateLimit(env, auth.client.id, 'screenshot');
  await getOwnedReport(env, reportId, auth.client.id);
  const attachment = await getAttachment(env, attachmentId);
  if (!attachment || attachment.report_id !== reportId) throw new HttpError(404, 'Screenshot not found.');
  const object = await env.SCREENSHOTS.get(attachment.r2_key);
  if (!object) throw new HttpError(404, 'Screenshot not found.');
  const headers = new Headers(corsHeaders(env));
  object.writeHttpMetadata(headers);
  headers.set('cache-control', 'private, max-age=300');
  headers.set('content-disposition', `inline; filename="${safeFilename(attachment.filename)}"`);
  headers.set('etag', object.httpEtag);
  return new Response(object.body, { status: 200, headers });
}

async function parseSubmission(request: Request): Promise<{
  type: string;
  title: string;
  body: string;
  diagnostics: unknown;
  githubUserPending: boolean;
  screenshot: File | null;
}> {
  const contentType = request.headers.get('content-type') || '';
  if (contentType.includes('multipart/form-data')) {
    const form = await request.formData();
    const screenshot = form.get('screenshot');
    return {
      type: cleanText(form.get('type'), 32),
      title: cleanTitle(form.get('title')),
      body: cleanText(form.get('body'), MAX_BODY_LENGTH),
      diagnostics: form.get('diagnostics'),
      githubUserPending: form.get('githubUserPending') === 'true',
      screenshot: screenshot instanceof File && screenshot.size ? screenshot : null
    };
  }
  if (!contentType.includes('application/json')) throw new HttpError(415, 'Use JSON or multipart form data.');
  const payload = await request.json<Record<string, unknown>>();
  return {
    type: cleanText(payload.type, 32),
    title: cleanTitle(payload.title),
    body: cleanText(payload.body, MAX_BODY_LENGTH),
    diagnostics: payload.diagnostics,
    githubUserPending: payload.githubUserPending === true,
    screenshot: null
  };
}

async function validateScreenshot(file: File): Promise<File> {
  if (!IMAGE_TYPES.has(file.type)) throw new HttpError(415, 'Screenshots must be PNG, JPEG, or WebP.');
  if (!file.size || file.size > MAX_SCREENSHOT_BYTES) throw new HttpError(413, 'Screenshots must be 5 MiB or smaller.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const ascii = new TextDecoder('latin1').decode(bytes);
  const isPng = bytes[0] === 0x89 && ascii.slice(1, 4) === 'PNG' && !ascii.includes('acTL');
  const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  const isWebp = ascii.slice(0, 4) === 'RIFF' && ascii.slice(8, 12) === 'WEBP' && !ascii.includes('ANIM');
  if (
    (file.type === 'image/png' && !isPng) ||
    (file.type === 'image/jpeg' && !isJpeg) ||
    (file.type === 'image/webp' && !isWebp)
  ) throw new HttpError(415, 'The screenshot content does not match its file type or is animated.');
  return new File([bytes], safeFilename(file.name || 'screenshot'), { type: file.type });
}

function publicReport(report: ReportRow): object {
  return {
    id: report.id,
    type: report.type,
    title: report.title,
    status: report.status,
    targetVersion: report.target_version,
    dispatchStatus: report.dispatch_status,
    externalIssueUrl: report.external_issue_url,
    createdAt: report.created_at,
    updatedAt: report.updated_at,
    closedAt: report.closed_at
  };
}

function publicMessage(message: { id: string; sender: string; body: string; created_at: string }): object {
  return { id: message.id, sender: message.sender, body: message.body, createdAt: message.created_at };
}

function safeFilename(value: string): string {
  return value.replace(/[^a-z0-9._-]/gi, '_').slice(0, 120) || 'screenshot';
}
