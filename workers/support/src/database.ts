import { CLOSED_STATUSES, RATE_LIMITS, RETENTION_DAYS } from './constants';
import { HttpError } from './http';
import type {
  AttachmentRow,
  ClientRow,
  MessageRow,
  MessageSender,
  ReportRow,
  ReportStatus,
  ReportType,
  SupportEnv
} from './types';

export async function enforceRateLimit(
  env: SupportEnv,
  key: string,
  action: keyof typeof RATE_LIMITS
): Promise<void> {
  const limit = RATE_LIMITS[action];
  const cutoff = new Date(Date.now() - limit.seconds * 1000).toISOString();
  const count = await env.DB.prepare(`
    SELECT COUNT(*) AS count FROM rate_events
    WHERE key = ? AND action = ? AND created_at >= ?
  `).bind(key, action, cutoff).first<{ count: number }>();
  if (Number(count?.count || 0) >= limit.count) throw new HttpError(429, 'Please wait before trying again.');
  await env.DB.prepare('INSERT INTO rate_events (key, action, created_at) VALUES (?, ?, ?)')
    .bind(key, action, new Date().toISOString())
    .run();
}

export async function createReport(
  env: SupportEnv,
  input: {
    clientId: string;
    type: ReportType;
    title: string;
    body: string;
    diagnostics: string | null;
    githubUserPending: boolean;
  }
): Promise<{ report: ReportRow; message: MessageRow }> {
  const now = new Date().toISOString();
  const reportId = crypto.randomUUID();
  const messageId = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare(`
      INSERT INTO reports (
        id, client_id, type, title, diagnostics_json, github_user_pending, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      reportId,
      input.clientId,
      input.type,
      input.title,
      input.diagnostics,
      input.githubUserPending ? 1 : 0,
      now,
      now
    ),
    env.DB.prepare(`
      INSERT INTO messages (id, report_id, sender, body, created_at)
      VALUES (?, ?, 'user', ?, ?)
    `).bind(messageId, reportId, input.body, now)
  ]);
  return {
    report: (await getReport(env, reportId))!,
    message: (await getMessage(env, messageId))!
  };
}

export async function createMessage(
  env: SupportEnv,
  reportId: string,
  sender: MessageSender,
  body: string
): Promise<MessageRow> {
  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  await env.DB.batch([
    env.DB.prepare(`
      INSERT INTO messages (id, report_id, sender, body, created_at)
      VALUES (?, ?, ?, ?, ?)
    `).bind(id, reportId, sender, body, now),
    env.DB.prepare(`
      UPDATE reports
      SET updated_at = ?, user_read_at = CASE WHEN ? = 'staff' THEN NULL ELSE user_read_at END
      WHERE id = ?
    `).bind(now, sender, reportId)
  ]);
  return (await getMessage(env, id))!;
}

export async function addAttachment(
  env: SupportEnv,
  reportId: string,
  messageId: string,
  file: File
): Promise<AttachmentRow> {
  const id = crypto.randomUUID();
  const extension = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg';
  const r2Key = `reports/${reportId}/${id}.${extension}`;
  const now = new Date().toISOString();
  await env.SCREENSHOTS.put(r2Key, file.stream(), {
    httpMetadata: { contentType: file.type },
    customMetadata: { reportId, attachmentId: id }
  });
  try {
    await env.DB.prepare(`
      INSERT INTO attachments (
        id, report_id, message_id, r2_key, filename, content_type, byte_size, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(id, reportId, messageId, r2Key, file.name || `screenshot.${extension}`, file.type, file.size, now).run();
  } catch (error) {
    await env.SCREENSHOTS.delete(r2Key);
    throw error;
  }
  return (await getAttachment(env, id))!;
}

export async function getReport(env: SupportEnv, id: string): Promise<ReportRow | null> {
  return env.DB.prepare('SELECT * FROM reports WHERE id = ? LIMIT 1').bind(id).first<ReportRow>();
}

export async function getOwnedReport(env: SupportEnv, id: string, clientId: string): Promise<ReportRow> {
  const report = await env.DB.prepare('SELECT * FROM reports WHERE id = ? AND client_id = ? LIMIT 1')
    .bind(id, clientId)
    .first<ReportRow>();
  if (!report) throw new HttpError(404, 'Report not found.');
  return report;
}

export async function getReportByThread(env: SupportEnv, threadId: string): Promise<ReportRow | null> {
  return env.DB.prepare('SELECT * FROM reports WHERE discord_thread_id = ? LIMIT 1')
    .bind(threadId)
    .first<ReportRow>();
}

export async function listReports(env: SupportEnv, clientId: string): Promise<ReportRow[]> {
  const result = await env.DB.prepare(`
    SELECT * FROM reports WHERE client_id = ? ORDER BY updated_at DESC LIMIT 100
  `).bind(clientId).all<ReportRow>();
  return result.results || [];
}

export async function listMessages(env: SupportEnv, reportId: string): Promise<MessageRow[]> {
  const result = await env.DB.prepare(`
    SELECT * FROM messages WHERE report_id = ? ORDER BY created_at ASC
  `).bind(reportId).all<MessageRow>();
  return result.results || [];
}

export async function listAttachments(env: SupportEnv, reportId: string): Promise<AttachmentRow[]> {
  const result = await env.DB.prepare('SELECT * FROM attachments WHERE report_id = ? ORDER BY created_at ASC')
    .bind(reportId)
    .all<AttachmentRow>();
  return result.results || [];
}

export async function getAttachment(env: SupportEnv, id: string): Promise<AttachmentRow | null> {
  return env.DB.prepare('SELECT * FROM attachments WHERE id = ? LIMIT 1').bind(id).first<AttachmentRow>();
}

async function getMessage(env: SupportEnv, id: string): Promise<MessageRow | null> {
  return env.DB.prepare('SELECT * FROM messages WHERE id = ? LIMIT 1').bind(id).first<MessageRow>();
}

export async function updateReportStatus(
  env: SupportEnv,
  reportId: string,
  status: ReportStatus,
  targetVersion = ''
): Promise<void> {
  const now = new Date().toISOString();
  const closedAt = CLOSED_STATUSES.has(status) ? now : null;
  await env.DB.prepare(`
    UPDATE reports
    SET status = ?, target_version = ?, updated_at = ?, closed_at = ?
    WHERE id = ?
  `).bind(status, targetVersion, now, closedAt, reportId).run();
}

export async function markRead(env: SupportEnv, reportId: string): Promise<void> {
  await env.DB.prepare('UPDATE reports SET user_read_at = ? WHERE id = ?')
    .bind(new Date().toISOString(), reportId)
    .run();
}

export async function clearGithubUserPending(env: SupportEnv, reportId: string): Promise<void> {
  await env.DB.prepare('UPDATE reports SET github_user_pending = 0 WHERE id = ?').bind(reportId).run();
}

export async function deleteReportData(env: SupportEnv, reportId: string): Promise<void> {
  const attachments = await listAttachments(env, reportId);
  if (attachments.length) await env.SCREENSHOTS.delete(attachments.map((item) => item.r2_key));
  await env.DB.prepare('DELETE FROM reports WHERE id = ?').bind(reportId).run();
}

export async function cleanupExpired(env: SupportEnv): Promise<ReportRow[]> {
  const cutoff = new Date(Date.now() - RETENTION_DAYS * 86_400_000).toISOString();
  const result = await env.DB.prepare(`
    SELECT * FROM reports WHERE closed_at IS NOT NULL AND closed_at < ? LIMIT 50
  `).bind(cutoff).all<ReportRow>();
  await env.DB.prepare('DELETE FROM rate_events WHERE created_at < ?')
    .bind(new Date(Date.now() - 2 * 86_400_000).toISOString())
    .run();
  return result.results || [];
}

export async function pendingDispatches(env: SupportEnv): Promise<ReportRow[]> {
  const result = await env.DB.prepare(`
    SELECT * FROM reports
    WHERE dispatch_status IN ('pending', 'failed')
      OR external_issue_dispatch_status IN ('pending', 'failed')
    ORDER BY created_at ASC LIMIT 20
  `).all<ReportRow>();
  return result.results || [];
}

export async function clientForReport(env: SupportEnv, report: ReportRow): Promise<ClientRow | null> {
  return env.DB.prepare('SELECT * FROM clients WHERE id = ? LIMIT 1').bind(report.client_id).first<ClientRow>();
}
