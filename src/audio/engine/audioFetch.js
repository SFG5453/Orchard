const DEFAULT_CHUNK_BYTES = 512 * 1024;
const DEFAULT_CONCURRENCY = 6;
const MAX_RANGE_COUNT = 64;
const RANGE_RETRY_COUNT = 1;

class RangeDownloadUnsupportedError extends Error {
  constructor(message) {
    super(message);
    this.name = 'RangeDownloadUnsupportedError';
  }
}

function positiveInteger(value) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : 0;
}

function effectiveChunkBytes(totalLength, requestedChunkBytes) {
  const minimum = Math.max(64 * 1024, positiveInteger(requestedChunkBytes) || DEFAULT_CHUNK_BYTES);
  return Math.max(minimum, Math.ceil(totalLength / MAX_RANGE_COUNT));
}

function rangeRequests(totalLength, chunkBytes) {
  const ranges = [];
  for (let start = 0; start < totalLength; start += chunkBytes) {
    ranges.push({
      start,
      end: Math.min(totalLength - 1, start + chunkBytes - 1)
    });
  }
  return ranges;
}

function validateContentRange(value, expectedStart, expectedEnd, totalLength) {
  if (!value) return;

  const match = /^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i.exec(value.trim());
  if (!match) throw new Error(`Audio analysis received an invalid Content-Range: ${value}`);

  const [, startText, endText, totalText] = match;
  const start = Number(startText);
  const end = Number(endText);
  const total = totalText === '*' ? totalLength : Number(totalText);
  if (start !== expectedStart || end !== expectedEnd || total !== totalLength) {
    throw new Error(`Audio analysis received the wrong byte range: ${value}`);
  }
}

async function fetchWholeFile(fetchImpl, url, signal) {
  const response = await fetchImpl(url, { signal });
  if (!response.ok) throw new Error(`Audio analysis fetch failed with HTTP ${response.status}`);
  return response.arrayBuffer();
}

async function fetchRange(fetchImpl, url, range, totalLength, signal) {
  const expectedLength = range.end - range.start + 1;
  let lastError = null;

  for (let attempt = 0; attempt <= RANGE_RETRY_COUNT; attempt += 1) {
    try {
      const response = await fetchImpl(url, {
        signal,
        headers: { Range: `bytes=${range.start}-${range.end}` }
      });
      if (response.status === 200) {
        throw new RangeDownloadUnsupportedError('Audio analysis server ignored the Range header');
      }
      if (response.status !== 206) {
        throw new Error(`Audio analysis range fetch failed with HTTP ${response.status}`);
      }

      validateContentRange(
        response.headers.get('content-range'),
        range.start,
        range.end,
        totalLength
      );
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.byteLength !== expectedLength) {
        throw new Error(
          `Audio analysis range returned ${bytes.byteLength} bytes; expected ${expectedLength}`
        );
      }
      return bytes;
    } catch (error) {
      if (error?.name === 'AbortError' || error instanceof RangeDownloadUnsupportedError) throw error;
      lastError = error;
    }
  }

  throw lastError || new Error('Audio analysis range fetch failed');
}

async function fetchByRanges(fetchImpl, url, totalLength, options) {
  const chunkBytes = effectiveChunkBytes(totalLength, options.chunkBytes);
  const ranges = rangeRequests(totalLength, chunkBytes);
  const chunks = new Array(ranges.length);
  const workerCount = Math.min(
    ranges.length,
    Math.max(1, positiveInteger(options.concurrency) || DEFAULT_CONCURRENCY)
  );
  const controller = new AbortController();
  const externalSignal = options.signal;
  const forwardAbort = () => controller.abort(externalSignal?.reason);
  if (externalSignal?.aborted) forwardAbort();
  else externalSignal?.addEventListener('abort', forwardAbort, { once: true });

  let nextIndex = 1;
  async function worker() {
    while (!controller.signal.aborted) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= ranges.length) return;
      chunks[index] = await fetchRange(
        fetchImpl,
        url,
        ranges[index],
        totalLength,
        controller.signal
      );
    }
  }

  try {
    // Probe one bounded request before starting the parallel workers. This avoids
    // opening several full-file responses if a server advertises byte ranges
    // but silently ignores the Range header.
    chunks[0] = await fetchRange(fetchImpl, url, ranges[0], totalLength, controller.signal);
    const remainingWorkers = Math.min(workerCount, ranges.length - 1);
    await Promise.all(Array.from({ length: remainingWorkers }, () => worker()));
  } catch (error) {
    controller.abort(error);
    throw error;
  } finally {
    externalSignal?.removeEventListener('abort', forwardAbort);
  }

  const merged = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged.buffer;
}

/**
 * Downloads an encoded audio file for offline analysis. Orchard's loopback
 * stream proxy advertises the exact file length and supports byte ranges, so
 * large files are split across a small number of concurrent requests instead
 * of waiting for YouTube's full-response playback throttling.
 */
export async function downloadAudioFile(url, options = {}) {
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  if (typeof fetchImpl !== 'function') throw new Error('Audio analysis fetch is unavailable');

  let head;
  try {
    head = await fetchImpl(url, { method: 'HEAD', signal: options.signal });
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    return fetchWholeFile(fetchImpl, url, options.signal);
  }

  const totalLength = positiveInteger(head.headers.get('content-length'));
  const supportsRanges = /\bbytes\b/i.test(head.headers.get('accept-ranges') || '');
  if (!head.ok || !totalLength || !supportsRanges) {
    return fetchWholeFile(fetchImpl, url, options.signal);
  }

  try {
    return await fetchByRanges(fetchImpl, url, totalLength, options);
  } catch (error) {
    if (error instanceof RangeDownloadUnsupportedError) {
      return fetchWholeFile(fetchImpl, url, options.signal);
    }
    throw error;
  }
}
