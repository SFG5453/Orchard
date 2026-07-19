import { MAX_BODY_LENGTH, MAX_DIAGNOSTICS_LENGTH, MAX_TITLE_LENGTH } from './constants';

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function corsHeaders(env: Env): HeadersInit {
  return {
    'access-control-allow-origin': env.ALLOWED_ORIGIN || '*',
    'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
    'access-control-allow-headers': 'authorization, content-type, x-github-user-token',
    'access-control-max-age': '86400',
    'x-content-type-options': 'nosniff'
  };
}

export function json(env: Env, value: unknown, status = 200): Response {
  return Response.json(value, {
    status,
    headers: {
      ...corsHeaders(env),
      'cache-control': 'no-store'
    }
  });
}

export function empty(env: Env, status = 204): Response {
  return new Response(null, { status, headers: corsHeaders(env) });
}

export function cleanText(value: unknown, maxLength = MAX_BODY_LENGTH): string {
  return String(value ?? '').replace(/\u0000/g, '').trim().slice(0, maxLength);
}

export function cleanTitle(value: unknown): string {
  return cleanText(value, MAX_TITLE_LENGTH).replace(/\s+/g, ' ');
}

export function cleanDiagnostics(value: unknown): string | null {
  if (!value) return null;
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (text.length > MAX_DIAGNOSTICS_LENGTH) throw new HttpError(413, 'Diagnostics are too large.');
  try {
    const parsed = JSON.parse(text) as unknown;
    return JSON.stringify(parsed);
  } catch {
    throw new HttpError(400, 'Diagnostics must be valid JSON.');
  }
}

export function requestIp(request: Request): string {
  return request.headers.get('cf-connecting-ip') || 'unknown';
}

export function errorResponse(env: Env, error: unknown): Response {
  if (error instanceof HttpError) return json(env, { ok: false, error: error.message }, error.status);
  const message = error instanceof Error ? error.message : String(error);
  console.error(JSON.stringify({ message: 'support request failed', error: message }));
  return json(env, { ok: false, error: 'Support is temporarily unavailable.' }, 500);
}
