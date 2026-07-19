import { secureHashEqual, sha256 } from './crypto';
import { HttpError } from './http';
import type { AuthenticatedClient, ClientRow, SupportEnv } from './types';

export async function authenticate(request: Request, env: SupportEnv): Promise<AuthenticatedClient> {
  const authorization = request.headers.get('authorization') || '';
  const match = authorization.match(/^Bearer\s+([a-f0-9-]{36})\.([a-f0-9]{64})$/i);
  if (!match) throw new HttpError(401, 'Support identity is missing or invalid.');

  const client = await env.DB.prepare('SELECT * FROM clients WHERE id = ? LIMIT 1')
    .bind(match[1])
    .first<ClientRow>();
  if (!client || !(await secureHashEqual(match[2], client.secret_hash))) {
    throw new HttpError(401, 'Support identity is missing or invalid.');
  }

  const now = new Date().toISOString();
  await env.DB.prepare('UPDATE clients SET last_seen_at = ? WHERE id = ?').bind(now, client.id).run();
  return { client: { ...client, last_seen_at: now }, token: match[2] };
}

export async function createClient(env: SupportEnv): Promise<{ clientId: string; secret: string }> {
  const clientId = crypto.randomUUID();
  const secretBytes = new Uint8Array(32);
  crypto.getRandomValues(secretBytes);
  const secret = [...secretBytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  const now = new Date().toISOString();
  await env.DB.prepare(`
    INSERT INTO clients (id, secret_hash, created_at, last_seen_at)
    VALUES (?, ?, ?, ?)
  `).bind(clientId, await sha256(secret), now, now).run();
  return { clientId, secret };
}
