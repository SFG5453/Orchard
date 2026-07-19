import { REPORT_STATUSES, REPORT_TYPE_LABELS } from './constants';
import { createMessage, getReport, listAttachments, listMessages, updateReportStatus } from './database';
import { verifyDiscordSignature } from './crypto';
import { closeGithubIssue, createGithubIssue } from './github';
import { cleanText, HttpError } from './http';
import type { AttachmentRow, DiscordInteraction, ReportRow, ReportStatus, SupportEnv } from './types';

const discordApi = 'https://discord.com/api/v10';

export async function dispatchReport(env: SupportEnv, reportId: string): Promise<void> {
  const report = await getReport(env, reportId);
  if (!report) return;
  if (!report.discord_thread_id) await dispatchDiscordThread(env, report);
  await createGithubIssue(env, report.id);
}

async function dispatchDiscordThread(env: SupportEnv, report: ReportRow): Promise<void> {
  if (!env.DISCORD_BOT_TOKEN || !env.DISCORD_FORUM_CHANNEL_ID) {
    await env.DB.prepare(`UPDATE reports SET dispatch_status = 'failed' WHERE id = ?`).bind(report.id).run();
    return;
  }

  try {
    if (report.dispatch_status === 'failed') {
      const recoveredThreadId = await findActiveDiscordThread(env, report);
      if (recoveredThreadId) {
        await mapDiscordThread(env, report.id, recoveredThreadId);
        console.log(JSON.stringify({
          message: 'recovered existing discord support thread',
          reportId: report.id,
          threadId: recoveredThreadId
        }));
        return;
      }
    }

    const [messages, attachments] = await Promise.all([
      listMessages(env, report.id),
      listAttachments(env, report.id)
    ]);
    const initial = messages[0];
    if (!initial) return;
    const body = {
      name: threadName(report),
      auto_archive_duration: 10080,
      message: {
        content: reportMessage(report, initial.body),
        allowed_mentions: { parse: [] },
        attachments: attachments.slice(0, 1).map((attachment, index) => ({
          id: index,
          filename: attachment.filename,
          description: 'User-provided screenshot'
        }))
      }
    };
    const form = new FormData();
    form.set('payload_json', JSON.stringify(body));
    if (attachments[0]) {
      const object = await env.SCREENSHOTS.get(attachments[0].r2_key);
      if (object) form.set('files[0]', new File([await object.arrayBuffer()], attachments[0].filename, {
        type: attachments[0].content_type
      }));
    }
    const response = await discordRequest(env, `/channels/${env.DISCORD_FORUM_CHANNEL_ID}/threads`, {
      method: 'POST',
      body: form
    });
    const payload = await response.json<{ id?: string; message?: { id?: string } }>();
    if (!payload.id) throw new Error('Discord did not return a thread ID.');
    let mapped = false;
    try {
      mapped = await mapDiscordThread(env, report.id, payload.id);
    } catch (error) {
      await deleteDiscordThread(env, payload.id);
      throw error;
    }
    if (!mapped) {
      await deleteDiscordThread(env, payload.id);
      return;
    }

    let discordMessageId = payload.message?.id || null;
    if (!discordMessageId) {
      try {
        discordMessageId = await postDiscordMessage(
          env,
          payload.id,
          {
            content: reportMessage(report, initial.body),
            allowed_mentions: { parse: [] }
          },
          attachments[0]
        );
      } catch (error) {
        console.error(JSON.stringify({
          message: 'discord starter message fallback failed',
          reportId: report.id,
          threadId: payload.id,
          error: error instanceof Error ? error.message : String(error)
        }));
      }
    }
    if (discordMessageId) {
      await env.DB.prepare('UPDATE messages SET discord_message_id = ? WHERE id = ?')
        .bind(discordMessageId, initial.id)
        .run();
    }
  } catch (error) {
    await env.DB.prepare(`UPDATE reports SET dispatch_status = 'failed' WHERE id = ?`).bind(report.id).run();
    console.error(JSON.stringify({
      message: 'discord report dispatch failed',
      reportId: report.id,
      error: error instanceof Error ? error.message : String(error)
    }));
  }
}

async function findActiveDiscordThread(env: SupportEnv, report: ReportRow): Promise<string | null> {
  if (!env.DISCORD_GUILD_ID) return null;
  const response = await discordRequest(env, `/guilds/${env.DISCORD_GUILD_ID}/threads/active`, { method: 'GET' });
  const payload = await response.json<{ threads?: Array<{ id?: string; name?: string }> }>();
  const expectedName = threadName(report);
  return payload.threads?.find((thread) => thread.id && thread.name === expectedName)?.id || null;
}

async function mapDiscordThread(env: SupportEnv, reportId: string, threadId: string): Promise<boolean> {
  await env.DB.prepare(`
    UPDATE reports
    SET discord_thread_id = ?, dispatch_status = 'sent', updated_at = ?
    WHERE id = ? AND (discord_thread_id IS NULL OR discord_thread_id = ?)
  `).bind(threadId, new Date().toISOString(), reportId, threadId).run();
  return (await getReport(env, reportId))?.discord_thread_id === threadId;
}

export async function postReportMessage(
  env: SupportEnv,
  report: ReportRow,
  body: string,
  messageId?: string,
  label = 'User reply'
): Promise<void> {
  if (!report.discord_thread_id) {
    await dispatchReport(env, report.id);
    const dispatched = await getReport(env, report.id);
    if (dispatched?.discord_thread_id) await postReportMessage(env, dispatched, body, messageId, label);
    return;
  }
  const attachment = messageId
    ? (await listAttachments(env, report.id)).find((item) => item.message_id === messageId)
    : undefined;
  const discordMessageId = await postDiscordMessage(env, report.discord_thread_id, {
    content: `**${label}**\n${body}`,
    allowed_mentions: { parse: [] }
  }, attachment);
  if (messageId && discordMessageId) {
    await env.DB.prepare('UPDATE messages SET discord_message_id = ? WHERE id = ?')
      .bind(discordMessageId, messageId)
      .run();
  }
}

async function postDiscordMessage(
  env: SupportEnv,
  threadId: string,
  payload: { content: string; allowed_mentions: { parse: string[] } },
  attachment?: AttachmentRow
): Promise<string | null> {
  const object = attachment ? await env.SCREENSHOTS.get(attachment.r2_key) : null;
  const messagePayload = {
    ...payload,
    attachments: attachment && object ? [{
      id: 0,
      filename: attachment.filename,
      description: 'User-provided screenshot'
    }] : []
  };
  const form = new FormData();
  form.set('payload_json', JSON.stringify(messagePayload));
  if (attachment && object) {
    form.set('files[0]', new File([await object.arrayBuffer()], attachment.filename, {
      type: attachment.content_type
    }));
  }
  const response = await discordRequest(env, `/channels/${threadId}/messages`, {
    method: 'POST',
    body: form
  });
  const discordMessage = await response.json<{ id?: string }>();
  return discordMessage.id || null;
}

export async function deleteDiscordThread(env: SupportEnv, threadId: string | null, strict = false): Promise<void> {
  if (!threadId) return;
  try {
    await discordRequest(env, `/channels/${threadId}`, { method: 'DELETE' });
  } catch (error) {
    console.error(JSON.stringify({
      message: 'discord thread deletion failed',
      threadId,
      error: error instanceof Error ? error.message : String(error)
    }));
    if (strict) throw error;
  }
}

export async function handleDiscordInteraction(
  request: Request,
  env: SupportEnv,
  ctx?: ExecutionContext
): Promise<Response> {
  const signature = request.headers.get('x-signature-ed25519') || '';
  const timestamp = request.headers.get('x-signature-timestamp') || '';
  const rawBody = await request.text();
  if (!(await verifyDiscordSignature(env.DISCORD_PUBLIC_KEY, signature, timestamp, rawBody))) {
    throw new HttpError(401, 'Invalid Discord signature.');
  }

  const interaction = JSON.parse(rawBody) as DiscordInteraction;
  if (interaction.type === 1) return Response.json({ type: 1 });
  if (interaction.type !== 2) return interactionReply('Unsupported interaction.');
  if (interaction.guild_id !== env.DISCORD_GUILD_ID) return interactionReply('This command is not available here.');
  if (!hasStaffRole(interaction, env.DISCORD_STAFF_ROLE_IDS)) return interactionReply('You are not allowed to manage Orchard reports.');

  const report = interaction.channel_id
    ? await env.DB.prepare('SELECT * FROM reports WHERE discord_thread_id = ? LIMIT 1')
      .bind(interaction.channel_id)
      .first<ReportRow>()
    : null;
  if (!report) return interactionReply('This thread is not linked to an Orchard report.');

  const command = interaction.data?.name || '';
  const options = Object.fromEntries((interaction.data?.options || []).map((item) => [item.name, item.value || '']));
  const message = cleanText(options.message);
  if (!message) return interactionReply('A user-facing message is required.');

  if (command === 'reply') {
    await createMessage(env, report.id, 'staff', message);
    return interactionReply('Reply sent to Orchard.');
  }
  if (command === 'request-info') {
    await createMessage(env, report.id, 'staff', message);
    await updateReportStatus(env, report.id, 'waiting_on_user');
    return interactionReply('The report is now waiting on the user.');
  }
  if (command === 'reopen') {
    await createMessage(env, report.id, 'staff', message);
    await updateReportStatus(env, report.id, 'open');
    await updateThreadSafely(env, report.discord_thread_id, { archived: false, locked: false });
    return interactionReply('The report has been reopened.');
  }
  if (command === 'close') {
    const status = cleanText(options.status) as ReportStatus;
    if (!REPORT_STATUSES.has(status) || status === 'open' || status === 'waiting_on_user') {
      return interactionReply('Choose a valid closing status.');
    }
    const version = cleanText(options.version, 32);
    const closeDetails = [
      `Closed as ${status.replaceAll('_', ' ')} by ${staffName(interaction)}.`,
      `Reason: ${message}`,
      ...(version ? [`Target release: Orchard ${version}`] : [])
    ];
    const userMessage = closeDetails.join('\n\n');
    await createMessage(env, report.id, 'staff', userMessage);
    await updateReportStatus(env, report.id, status, version);
    await closeGithubIssue(env, report.id);
    await updateThreadSafely(env, report.discord_thread_id, { archived: true, locked: true });
    return interactionReply(`Report closed as ${status.replaceAll('_', ' ')}.`);
  }
  return interactionReply('Unknown support command.');
}

async function updateThread(env: SupportEnv, threadId: string | null, payload: object): Promise<void> {
  if (!threadId) return;
  await discordRequest(env, `/channels/${threadId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

async function updateThreadSafely(env: SupportEnv, threadId: string | null, payload: object): Promise<void> {
  try {
    await updateThread(env, threadId, payload);
  } catch (error) {
    console.error(JSON.stringify({
      message: 'discord support thread update failed',
      threadId,
      error: error instanceof Error ? error.message : String(error)
    }));
  }
}

async function discordRequest(env: SupportEnv, path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('authorization', `Bot ${env.DISCORD_BOT_TOKEN}`);
  headers.set('user-agent', 'OrchardSupport/1.0');
  const response = await fetch(`${discordApi}${path}`, { ...init, headers });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500);
    throw new Error(`Discord returned ${response.status}: ${detail}`);
  }
  return response;
}

function hasStaffRole(interaction: DiscordInteraction, configuredRoles: string): boolean {
  const allowed = new Set(configuredRoles.split(',').map((item) => item.trim()).filter(Boolean));
  return Boolean(interaction.member?.roles?.some((role) => allowed.has(role)));
}

function staffName(interaction: DiscordInteraction): string {
  return cleanText(
    interaction.member?.nick ||
    interaction.member?.user?.global_name ||
    interaction.member?.user?.username ||
    'Orchard Support',
    80
  );
}

function interactionReply(content: string): Response {
  return Response.json({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
}

function threadName(report: ReportRow): string {
  const label = REPORT_TYPE_LABELS[report.type];
  return `${label} · ${report.title} · ${report.id.slice(0, 8)}`.slice(0, 100);
}

function reportMessage(report: ReportRow, body: string): string {
  const diagnostics = report.diagnostics_json
    ? `\n\n**Diagnostics included**\n\`\`\`json\n${report.diagnostics_json.slice(0, 3500)}\n\`\`\``
    : '';
  return `**Orchard ${REPORT_TYPE_LABELS[report.type].toLowerCase()} report**\n**ID:** ${report.id}\n**Status:** ${report.status}\n\n${body}${diagnostics}`;
}
