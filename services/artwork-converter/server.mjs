// For some reason, Discord needs a hosted gif to play the animation, so my poor FreeBSD server has to convert them.
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, stat, unlink } from 'node:fs/promises';
import { createServer } from 'node:http';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { spawn } from 'node:child_process';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';

// This service assumes a FreeBSD host, hense the /usr/local paths.

const host = process.env.ARTWORK_HOST || '127.0.0.1';
const port = Number(process.env.ARTWORK_PORT || 8791);
const cacheDir = process.env.ARTWORK_CACHE_DIR || '/var/cache/orchard-artwork';
const tokenFile = process.env.ARTWORK_TOKEN_FILE || '/usr/local/etc/orchard-artwork/token';
const maxInputBytes = Number(process.env.ARTWORK_MAX_INPUT_BYTES || 32 * 1024 * 1024);
const targetOutputBytes = Number(process.env.ARTWORK_TARGET_OUTPUT_BYTES || 9_950_000);
const maxConcurrentJobs = Number(process.env.ARTWORK_MAX_JOBS || 2);
const encodingVersion = '8';
const token = (await readFile(tokenFile, 'utf8')).trim();
const jobs = new Map();

if (token.length < 32) throw new Error('Artwork converter token is missing or too short.');
await mkdir(cacheDir, { recursive: true });

const server = createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);

    if (requestUrl.pathname === '/health') {
      return sendJson(response, 200, { ok: true, service: 'orchard-artwork-converter' });
    }

    if (requestUrl.pathname !== '/convert') return sendText(response, 404, 'Not found.');
    if (request.method !== 'GET' && request.method !== 'HEAD') return sendText(response, 405, 'Method not allowed.');
    if (!authorized(request.headers.authorization)) return sendText(response, 401, 'Unauthorized.');

    const sourceUrl = validSourceUrl(requestUrl.searchParams.get('url'));
    if (!sourceUrl) return sendText(response, 400, 'Unsupported artwork URL.');

    const cacheKey = createHash('sha256')
      .update(`${encodingVersion}\0${sourceUrl.href}`)
      .digest('hex');
    const artworkPath = `${cacheDir}/${cacheKey}.gif`;
    if (!(await regularFile(artworkPath))) {
      if (!jobs.has(cacheKey) && jobs.size >= maxConcurrentJobs) {
        return sendText(response, 503, 'Converter is busy.');
      }

      if (!jobs.has(cacheKey)) {
        const job = convertArtwork(sourceUrl, artworkPath).finally(() => jobs.delete(cacheKey));
        jobs.set(cacheKey, job);
      }
      await jobs.get(cacheKey);
    }

    return sendArtwork(response, artworkPath, request.method === 'HEAD');
  } catch (error) {
    console.error(JSON.stringify({
      message: 'artwork conversion request failed',
      error: error instanceof Error ? error.message : String(error)
    }));
    return sendText(response, 502, 'Artwork conversion failed.');
  }
});

server.requestTimeout = 60_000;
server.headersTimeout = 10_000;
server.listen(port, host, () => {
  console.log(JSON.stringify({ message: 'artwork converter listening', host, port }));
});

function authorized(header = '') {
  const supplied = Buffer.from(String(header).replace(/^Bearer\s+/i, '').trim());
  const expected = Buffer.from(token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

function validSourceUrl(value) {
  if (!value || value.length > 4096) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.hostname !== 'mvod.itunes.apple.com') return null;
    if (!url.pathname.toLowerCase().endsWith('.mp4')) return null;
    url.hash = '';
    return url;
  } catch {
    return null;
  }
}

async function convertArtwork(sourceUrl, artworkPath) {
  const jobId = randomUUID();
  const inputPath = `${cacheDir}/.${jobId}.mp4`;
  const outputPath = `${cacheDir}/.${jobId}.gif`;

  try {
    await download(sourceUrl, inputPath, 0);
    await encodeGif(inputPath, outputPath);
    await rename(outputPath, artworkPath);
  } finally {
    await Promise.allSettled([unlink(inputPath), unlink(outputPath)]);
  }
}

async function download(url, path, redirectCount) {
  if (redirectCount > 3) throw new Error('Too many artwork redirects.');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20_000);

  try {
    const response = await fetch(url, { redirect: 'manual', signal: controller.signal });
    if (response.status >= 300 && response.status < 400) {
      const nextUrl = validSourceUrl(new URL(response.headers.get('location') || '', url).href);
      if (!nextUrl) throw new Error('Artwork redirected to an unsupported host.');
      return download(nextUrl, path, redirectCount + 1);
    }
    if (!response.ok || !response.body) throw new Error(`Artwork download returned ${response.status}.`);

    const declaredSize = Number(response.headers.get('content-length') || 0);
    if (declaredSize > maxInputBytes) throw new Error('Artwork input exceeds the download limit.');

    let received = 0;
    const limiter = new Transform({
      transform(chunk, _encoding, callback) {
        received += chunk.length;
        callback(received > maxInputBytes ? new Error('Artwork input exceeds the download limit.') : null, chunk);
      }
    });
    await pipeline(response.body, limiter, createWriteStream(path, { mode: 0o600 }));
  } finally {
    clearTimeout(timeout);
  }
}

// Discord needs a certain size, so we try different profiles that dont destroy the look
async function encodeGif(inputPath, outputPath) {
  const profiles = [
    { size: 512, fps: 24, colors: 92 },
    { size: 512, fps: 24, colors: 80 },
    { size: 448, fps: 24, colors: 112 },
    { size: 448, fps: 20, colors: 128 },
    { size: 384, fps: 20, colors: 112 },
    { size: 384, fps: 16, colors: 96 },
    { size: 352, fps: 16, colors: 96 },
    { size: 320, fps: 15, colors: 96 },
    { size: 320, fps: 12, colors: 80 },
    { size: 288, fps: 12, colors: 80 }
  ];

  for (const profile of profiles) {
    await runFfmpeg(inputPath, outputPath, profile);
    if ((await stat(outputPath)).size <= targetOutputBytes) return;
    await unlink(outputPath);
  }

  throw new Error('Could not encode animated GIF within the output limit.');
}

function runFfmpeg(inputPath, outputPath, profile) {
  const filter = [
    `fps=${profile.fps},scale=${profile.size}:${profile.size}:force_original_aspect_ratio=decrease:flags=lanczos,split[frames][palette_source]`,
    `[palette_source]palettegen=max_colors=${profile.colors}:stats_mode=diff[palette]`,
    '[frames][palette]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle'
  ].join(';');

  return new Promise((resolve, reject) => {
    const child = spawn('/usr/local/bin/ffmpeg', [
      '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-t', '8',
      '-i', inputPath, '-an', '-filter_complex', filter, '-loop', '0', outputPath
    ], { stdio: ['ignore', 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => { stderr = `${stderr}${chunk}`.slice(-3000); });
    const timeout = setTimeout(() => child.kill('SIGKILL'), 35_000);
    child.on('error', reject);
    child.on('close', (code, signal) => {
      clearTimeout(timeout);
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg failed (${signal || code}): ${stderr.trim()}`));
    });
  });
}

async function regularFile(path) {
  try {
    return (await stat(path)).isFile();
  } catch {
    return false;
  }
}

async function sendArtwork(response, path, headOnly) {
  const info = await stat(path);
  response.writeHead(200, {
    'cache-control': 'public, max-age=2592000, immutable',
    'content-length': info.size,
    'content-type': 'image/gif',
    'x-content-type-options': 'nosniff'
  });
  if (headOnly) return response.end();
  createReadStream(path).pipe(response);
}

function sendJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  response.end(body);
}

function sendText(response, status, message) {
  response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
  response.end(message);
}
