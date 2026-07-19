import { env, applyD1Migrations, SELF } from 'cloudflare:test';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { bytesToHex, verifyDiscordSignature } from '../src/crypto';
import { dispatchReport, handleDiscordInteraction, postReportMessage } from '../src/discord';
import { createGithubIssue } from '../src/github';
import type { ReportRow, SupportEnv } from '../src/types';

declare module 'cloudflare:test' {
  interface ProvidedEnv extends Env {
    TEST_MIGRATIONS: D1Migration[];
  }
}

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe('Orchard support API', () => {
  it('publishes the GitHub device-flow client ID without authentication', async () => {
    const response = await SELF.fetch('https://support.example.com/v1/github/config');
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ ok: true, clientId: 'Iv23licQ1byWIMP1hRNQ' });
  });

  it('creates an anonymous client and rejects a bad secret', async () => {
    const identity = await createIdentity();
    expect(identity.clientId).toMatch(/^[a-f0-9-]{36}$/);
    expect(identity.secret).toMatch(/^[a-f0-9]{64}$/);

    const response = await SELF.fetch('https://support.example.com/v1/reports', {
      headers: { authorization: `Bearer ${identity.clientId}.${'0'.repeat(64)}` }
    });
    expect(response.status).toBe(401);
  });

  it('creates, lists, reads, and deletes a report', async () => {
    const identity = await createIdentity();
    const authorization = bearer(identity);
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({
        type: 'bug',
        title: 'Playback stopped',
        body: 'Playback stopped after the first track.',
        diagnostics: { app: { version: '1.0.0' } }
      })
    });
    expect(created.status).toBe(200);
    const createdBody = await created.json<{ report: { id: string; status: string } }>();
    expect(createdBody.report.status).toBe('open');

    const listed = await SELF.fetch('https://support.example.com/v1/reports', {
      headers: { authorization }
    });
    const listedBody = await listed.json<{ reports: Array<{ id: string }> }>();
    expect(listedBody.reports.some((report) => report.id === createdBody.report.id)).toBe(true);

    const removed = await SELF.fetch(`https://support.example.com/v1/reports/${createdBody.report.id}`, {
      method: 'DELETE',
      headers: { authorization }
    });
    expect(removed.status).toBe(200);
  });

  it('rejects a screenshot whose bytes do not match its MIME type', async () => {
    const identity = await createIdentity();
    const form = new FormData();
    form.set('type', 'bug');
    form.set('title', 'Bad screenshot');
    form.set('body', 'The screenshot should be rejected.');
    form.set('screenshot', new File(['not a png'], 'fake.png', { type: 'image/png' }));
    const response = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity) },
      body: form
    });
    expect(response.status).toBe(415);
  });

  it('creates a report with a valid screenshot attachment', async () => {
    const identity = await createIdentity();
    const form = new FormData();
    form.set('type', 'bug');
    form.set('title', 'Screenshot included');
    form.set('body', 'The first report includes an image.');
    form.set('screenshot', new File([
      new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    ], 'orchard.png', { type: 'image/png' }));
    const response = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity) },
      body: form
    });
    expect(response.status).toBe(200);
    const body = await response.json<{ report: { id: string }, attachments: Array<{ filename: string }> }>();
    expect(body.report.id).toMatch(/^[a-f0-9-]{36}$/);
    expect(body.attachments).toHaveLength(1);
    expect(body.attachments[0]?.filename).toBe('orchard.png');
  });

  it('does not accept a user reply after a report is closed', async () => {
    const identity = await createIdentity();
    const authorization = bearer(identity);
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'feedback', title: 'Small thought', body: 'Hello Orchard.' })
    });
    const body = await created.json<{ report: { id: string } }>();
    await env.DB.prepare("UPDATE reports SET status = 'fixed', closed_at = ? WHERE id = ?")
      .bind(new Date().toISOString(), body.report.id)
      .run();
    const reply = await SELF.fetch(`https://support.example.com/v1/reports/${body.report.id}/messages`, {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({ body: 'One more detail.' })
    });
    expect(reply.status).toBe(409);
  });

  it('forwards a reply screenshot to its mapped Discord thread', async () => {
    const identity = await createIdentity();
    const authorization = bearer(identity);
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'bug', title: 'Visual issue', body: 'The artwork is clipped.' })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    const form = new FormData();
    form.set('body', 'Here is a screenshot.');
    form.set('screenshot', new File([
      new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    ], 'orchard.png', { type: 'image/png' }));
    const reply = await SELF.fetch(`https://support.example.com/v1/reports/${createdBody.report.id}/messages`, {
      method: 'POST',
      headers: { authorization },
      body: form
    });
    expect(reply.status).toBe(200);

    const threadId = 'reply-thread-1';
    await env.DB.prepare("UPDATE reports SET discord_thread_id = ?, dispatch_status = 'sent' WHERE id = ?")
      .bind(threadId, createdBody.report.id)
      .run();
    const report = await env.DB.prepare('SELECT * FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<ReportRow>();
    const message = await env.DB.prepare('SELECT message_id AS id FROM attachments WHERE report_id = ? LIMIT 1')
      .bind(createdBody.report.id)
      .first<{ id: string }>();
    expect(report).not.toBeNull();
    expect(message).not.toBeNull();

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({ id: 'discord-message-1' }));
    await postReportMessage({ ...env, DISCORD_BOT_TOKEN: 'token' } as SupportEnv, report!, 'Here is a screenshot.', message!.id);
    const init = fetchSpy.mock.calls[0]?.[1] as RequestInit;
    expect(fetchSpy.mock.calls[0]?.[0]).toBe(`https://discord.com/api/v10/channels/${threadId}/messages`);
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('files[0]')).toBeInstanceOf(File);
    const mapped = await env.DB.prepare('SELECT discord_message_id FROM messages WHERE id = ?')
      .bind(message!.id)
      .first<{ discord_message_id: string }>();
    expect(mapped?.discord_message_id).toBe('discord-message-1');
    fetchSpy.mockRestore();
  });

  it('posts the initial report body when Discord creates an empty thread', async () => {
    const identity = await createIdentity();
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity), 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'bug', title: 'Empty starter', body: 'This should appear in Discord.' })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    let discordCalls = 0;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      discordCalls += 1;
      return Response.json({ id: discordCalls === 1 ? 'thread-without-message' : 'fallback-message' });
    });

    await dispatchReport({
      ...env,
      DISCORD_BOT_TOKEN: 'token',
      DISCORD_FORUM_CHANNEL_ID: 'forum-1'
    } as SupportEnv, createdBody.report.id);

    const calls = fetchSpy.mock.calls.filter(([url]) => String(url).startsWith('https://discord.com/api/v10/'));
    expect(calls).toHaveLength(2);
    expect(calls[0]?.[0]).toBe('https://discord.com/api/v10/channels/forum-1/threads');
    expect(calls[1]?.[0]).toBe('https://discord.com/api/v10/channels/thread-without-message/messages');
    const fallbackBody = calls[1]?.[1] as RequestInit;
    const fallbackPayload = JSON.parse(String((fallbackBody.body as FormData).get('payload_json')));
    expect(fallbackPayload.content).toContain('This should appear in Discord.');
    const mapped = await env.DB.prepare('SELECT discord_thread_id, dispatch_status FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ discord_thread_id: string; dispatch_status: string }>();
    const message = await env.DB.prepare('SELECT discord_message_id FROM messages WHERE report_id = ? ORDER BY created_at LIMIT 1')
      .bind(createdBody.report.id)
      .first<{ discord_message_id: string }>();
    expect(mapped?.discord_thread_id).toBe('thread-without-message');
    expect(mapped?.dispatch_status).toBe('sent');
    expect(message?.discord_message_id).toBe('fallback-message');
    fetchSpy.mockRestore();
  });

  it('does not create another thread when the starter fallback fails', async () => {
    const identity = await createIdentity();
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity), 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'bug', title: 'Fallback failure', body: 'Keep one Discord thread.' })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    let discordCalls = 0;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      discordCalls += 1;
      if (discordCalls === 1) return Response.json({ id: 'persisted-thread' });
      return new Response('Could not post starter', { status: 500 });
    });
    const supportEnv = {
      ...env,
      DISCORD_BOT_TOKEN: 'token',
      DISCORD_FORUM_CHANNEL_ID: 'forum-1'
    } as SupportEnv;

    await dispatchReport(supportEnv, createdBody.report.id);
    await dispatchReport(supportEnv, createdBody.report.id);

    const report = await env.DB.prepare('SELECT discord_thread_id, dispatch_status FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ discord_thread_id: string; dispatch_status: string }>();
    expect(report).toEqual({ discord_thread_id: 'persisted-thread', dispatch_status: 'sent' });
    expect(discordCalls).toBe(2);
    fetchSpy.mockRestore();
  });

  it('recovers a matching active Discord thread before retrying a failed dispatch', async () => {
    const identity = await createIdentity();
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity), 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'bug', title: 'Recover me', body: 'The first dispatch lost its mapping.' })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    await env.DB.prepare("UPDATE reports SET dispatch_status = 'failed' WHERE id = ?")
      .bind(createdBody.report.id)
      .run();
    const expectedName = `Bug · Recover me · ${createdBody.report.id.slice(0, 8)}`;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({
      threads: [{ id: 'recovered-thread', name: expectedName }]
    }));

    await dispatchReport({
      ...env,
      DISCORD_BOT_TOKEN: 'token',
      DISCORD_FORUM_CHANNEL_ID: 'forum-1',
      DISCORD_GUILD_ID: 'guild-1'
    } as SupportEnv, createdBody.report.id);

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0]?.[0]).toBe('https://discord.com/api/v10/guilds/guild-1/threads/active');
    const report = await env.DB.prepare('SELECT discord_thread_id, dispatch_status FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ discord_thread_id: string; dispatch_status: string }>();
    expect(report).toEqual({ discord_thread_id: 'recovered-thread', dispatch_status: 'sent' });
    fetchSpy.mockRestore();
  });

  it('creates a linked GitHub issue for a report', async () => {
    const identity = await createIdentity();
    const issueNumber = uniqueIssueNumber(100000);
    const issueUrl = `https://github.com/owner/repository/issues/${issueNumber}`;
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity), 'content-type': 'application/json' },
      body: JSON.stringify({
        type: 'feature',
        title: 'Remember volume',
        body: 'Please keep volume between launches.',
        diagnostics: { app: { version: '1.2.0' } }
      })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    await env.DB.prepare("UPDATE reports SET discord_thread_id = ?, dispatch_status = 'sent' WHERE id = ?")
      .bind('discord-thread-1', createdBody.report.id)
      .run();
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return Response.json({ number: issueNumber, html_url: issueUrl });
    });

    await createGithubIssue({
      ...env,
      GITHUB_REPOSITORY: 'owner/repository',
      GITHUB_TOKEN: 'token'
    } as SupportEnv, createdBody.report.id);

    expect(fetchSpy).toHaveBeenCalledWith(
      'https://api.github.com/repos/owner/repository/issues',
      expect.objectContaining({ method: 'POST' })
    );
    const githubCall = fetchSpy.mock.calls.find(([url]) => String(url).startsWith('https://api.github.com/'));
    expect(githubCall).toBeTruthy();
    const init = githubCall?.[1] as RequestInit;
    const payload = JSON.parse(String(init.body));
    expect(payload.title).toBe('[Feature] Remember volume');
    expect(payload.labels).toEqual(['enhancement']);
    expect(payload.body).toContain('Please keep volume between launches.');
    expect(payload.body).toContain('Type: feature');
    expect(payload.body).not.toContain(createdBody.report.id);
    expect(payload.body).not.toContain('Discord thread ID: discord-thread-1');
    expect(payload.body).not.toContain('Diagnostics');
    const report = await env.DB.prepare('SELECT external_issue_number, external_issue_url, external_issue_dispatch_status FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ external_issue_number: number; external_issue_url: string; external_issue_dispatch_status: string }>();
    expect(report).toEqual({
      external_issue_number: issueNumber,
      external_issue_url: issueUrl,
      external_issue_dispatch_status: 'sent'
    });
    fetchSpy.mockRestore();
  });

  it('creates a linked issue with the reporter GitHub token when requested', async () => {
    const identity = await createIdentity();
    const authorization = bearer(identity);
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({
        type: 'feedback',
        title: 'Show my GitHub account',
        body: 'Attribute this public issue to me.',
        githubUserPending: true
      })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    const pending = await env.DB.prepare('SELECT github_user_pending FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ github_user_pending: number }>();
    expect(pending?.github_user_pending).toBe(1);

    const issueNumber = uniqueIssueNumber(120000);
    const issueUrl = `https://github.com/owner/repository/issues/${issueNumber}`;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => (
      Response.json({ number: issueNumber, html_url: issueUrl })
    ));
    const linked = await SELF.fetch(`https://support.example.com/v1/reports/${createdBody.report.id}/github`, {
      method: 'POST',
      headers: {
        authorization,
        'x-github-user-token': 'ghu_reporter_token_1234567890'
      }
    });
    expect(linked.status).toBe(200);
    const githubCall = fetchSpy.mock.calls.find(([url]) => String(url).startsWith('https://api.github.com/'));
    expect(githubCall).toBeTruthy();
    expect(new Headers(githubCall?.[1]?.headers).get('authorization'))
      .toBe('Bearer ghu_reporter_token_1234567890');
    const mapped = await env.DB.prepare(`
      SELECT external_issue_url, external_issue_dispatch_status, github_user_pending
      FROM reports WHERE id = ?
    `).bind(createdBody.report.id).first<{
      external_issue_url: string;
      external_issue_dispatch_status: string;
      github_user_pending: number;
    }>();
    expect(mapped).toEqual({
      external_issue_url: issueUrl,
      external_issue_dispatch_status: 'sent',
      github_user_pending: 0
    });
    fetchSpy.mockRestore();
  });

  it('creates readable GitHub issues for artist page requests', async () => {
    const identity = await createIdentity();
    const issueNumber = uniqueIssueNumber(110000);
    const issueUrl = `https://github.com/owner/repository/issues/${issueNumber}`;
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization: bearer(identity), 'content-type': 'application/json' },
      body: JSON.stringify({
        type: 'artist_page',
        title: 'Add a Chappell Roan page',
        body: 'Please add custom artist page art and styling.'
      })
    });
    expect(created.status).toBe(200);
    const createdBody = await created.json<{ report: { id: string; type: string } }>();
    expect(createdBody.report.type).toBe('artist_page');
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      return Response.json({ number: issueNumber, html_url: issueUrl });
    });

    await createGithubIssue({
      ...env,
      GITHUB_REPOSITORY: 'owner/repository',
      GITHUB_TOKEN: 'token'
    } as SupportEnv, createdBody.report.id);

    const githubCall = fetchSpy.mock.calls.find(([url]) => String(url).startsWith('https://api.github.com/'));
    expect(githubCall).toBeTruthy();
    const init = githubCall?.[1] as RequestInit;
    const payload = JSON.parse(String(init.body));
    expect(payload.title).toBe('[Artist page] Add a Chappell Roan page');
    expect(payload.labels).toEqual(['enhancement']);
    expect(payload.body).toContain('Type: artist_page');
    fetchSpy.mockRestore();
  });
});

describe('Discord verification', () => {
  it('accepts a fresh valid Ed25519 signature and rejects a changed body', async () => {
    const keys = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
    const publicKey = bytesToHex(new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey)));
    const timestamp = String(Math.floor(Date.now() / 1000));
    const body = JSON.stringify({ type: 1 });
    const signature = bytesToHex(new Uint8Array(await crypto.subtle.sign(
      'Ed25519',
      keys.privateKey,
      new TextEncoder().encode(`${timestamp}${body}`)
    )));
    expect(await verifyDiscordSignature(publicKey, signature, timestamp, body)).toBe(true);
    expect(await verifyDiscordSignature(publicKey, signature, timestamp, `${body} `)).toBe(false);
  });

  it('turns /close into a fixed report and a user-facing release message', async () => {
    const identity = await createIdentity();
    const authorization = bearer(identity);
    const created = await SELF.fetch('https://support.example.com/v1/reports', {
      method: 'POST',
      headers: { authorization, 'content-type': 'application/json' },
      body: JSON.stringify({ type: 'bug', title: 'Queue issue', body: 'The queue became empty.' })
    });
    const createdBody = await created.json<{ report: { id: string } }>();
    const threadId = '123456789012345678';
    const issueNumber = uniqueIssueNumber(200000);
    const issueUrl = `https://github.com/owner/repository/issues/${issueNumber}`;
    await env.DB.prepare(`
      UPDATE reports
      SET discord_thread_id = ?, dispatch_status = 'sent',
        external_issue_number = ?, external_issue_url = ?, external_issue_dispatch_status = 'sent'
      WHERE id = ?
    `).bind(threadId, issueNumber, issueUrl, createdBody.report.id)
      .run();

    const keys = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
    const publicKey = bytesToHex(new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey)));
    const interaction = JSON.stringify({
      type: 2,
      guild_id: 'guild-1',
      channel_id: threadId,
      member: { roles: ['staff-1'], nick: 'Casey', user: { id: 'user-1', username: 'tester' } },
      data: {
        name: 'close',
        options: [
          { name: 'status', value: 'fixed' },
          { name: 'message', value: 'Fixed and shipping in the next update.' },
          { name: 'version', value: '1.1.0' }
        ]
      }
    });
    const timestamp = String(Math.floor(Date.now() / 1000));
    const signature = bytesToHex(new Uint8Array(await crypto.subtle.sign(
      'Ed25519',
      keys.privateKey,
      new TextEncoder().encode(`${timestamp}${interaction}`)
    )));
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({ id: threadId }));

    const supportEnv = {
      ...env,
      DISCORD_APPLICATION_ID: 'app-1',
      DISCORD_BOT_TOKEN: 'token',
      DISCORD_FORUM_CHANNEL_ID: 'forum-1',
      DISCORD_GUILD_ID: 'guild-1',
      DISCORD_PUBLIC_KEY: publicKey,
      DISCORD_STAFF_ROLE_IDS: 'staff-1',
      GITHUB_REPOSITORY: 'owner/repository',
      GITHUB_TOKEN: 'github-token'
    } as SupportEnv;
    const response = await handleDiscordInteraction(new Request('https://support.example.com/discord/interactions', {
      method: 'POST',
      headers: {
        'x-signature-ed25519': signature,
        'x-signature-timestamp': timestamp
      },
      body: interaction
    }), supportEnv);
    expect(response.status).toBe(200);
    const report = await env.DB.prepare('SELECT status, target_version FROM reports WHERE id = ?')
      .bind(createdBody.report.id)
      .first<{ status: string; target_version: string }>();
    expect(report).toEqual({ status: 'fixed', target_version: '1.1.0' });
    const message = await env.DB.prepare("SELECT body FROM messages WHERE report_id = ? AND sender = 'staff'")
      .bind(createdBody.report.id)
      .first<{ body: string }>();
    expect(message?.body).toContain('Closed as fixed by Casey.');
    expect(message?.body).toContain('Reason: Fixed and shipping in the next update.');
    expect(message?.body).toContain('Target release: Orchard 1.1.0');
    expect(fetchSpy).toHaveBeenCalledWith(
      `https://discord.com/api/v10/channels/${threadId}`,
      expect.objectContaining({ method: 'PATCH' })
    );
    const githubCall = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'PATCH');
    expect(githubCall?.[0]).toBe(`https://api.github.com/repos/owner/repository/issues/${issueNumber}`);
    expect(githubCall?.[1]).toEqual(expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ state: 'closed' })
    }));
    fetchSpy.mockRestore();
  });
});

let createIdentityCounter = 0;

async function createIdentity(): Promise<{ clientId: string; secret: string }> {
  createIdentityCounter += 1;
  const response = await SELF.fetch('https://support.example.com/v1/clients', {
    method: 'POST',
    headers: { 'cf-connecting-ip': `192.0.2.${createIdentityCounter}` }
  });
  expect(response.status).toBe(201);
  return response.json();
}

function bearer(identity: { clientId: string; secret: string }): string {
  return `Bearer ${identity.clientId}.${identity.secret}`;
}

function uniqueIssueNumber(base: number): number {
  const random = new Uint32Array(1);
  crypto.getRandomValues(random);
  return base + Number(random[0] % 100000);
}
