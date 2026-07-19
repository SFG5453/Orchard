function editableTarget(target) {
  return target?.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName);
}

function normalizedKey(event) {
  if (event.code?.startsWith('Digit')) return event.code.slice(5);
  if (event.code?.startsWith('Numpad')) return event.code.slice(6);
  return String(event.key || '').toLowerCase();
}

function targetTrack(config) {
  const track = config?.targetTrack || {};
  if (!track.id) return null;

  return {
    id: track.id,
    title: track.title || 'Track',
    artist: track.artist || config.artistName || '',
    artists: track.artists || [track.artist || config.artistName || ''].filter(Boolean),
    album: track.album || '',
    thumbnail: track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`,
    type: track.type || 'song',
    mediaKind: track.mediaKind || 'audio',
    isAudioOnly: track.mediaKind !== 'video',
    musicVideoType: track.musicVideoType || ''
  };
}

function playAudio(url) {
  if (!url) return Promise.resolve();
  return new Promise((resolve) => {
    const audio = new Audio(url);
    audio.onended = resolve;
    audio.onerror = resolve;
    audio.play().catch(resolve);
  });
}

export function attachKeyEasterEgg(app, config) {
  const egg = config.features?.keyEasterEgg;
  const sequence = Array.isArray(egg?.keys) ? egg.keys.map(String) : [];
  if (!sequence.length) return () => { };

  let buffer = [];
  let active = false;

  async function activate() {
    if (active) return;
    active = true;

    try {
      app.stopCustomArtistIdlePreview?.();
      const audioUrl = (egg.audioAsset && config.assets?.[egg.audioAsset]) || egg.audioUrl;
      if (audioUrl) {
        await playAudio(audioUrl);
      }

      const track = targetTrack(egg);
      if (track) {
        await app.playTrack(track, {
          mediaKind: track.mediaKind,
          preserveQueue: true,
          sessionAction: 'artist-easter-egg'
        });
      }
    } finally {
      active = false;
    }
  }

  function onKeydown(event) {
    if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey || editableTarget(event.target)) return;

    buffer = [...buffer, normalizedKey(event)].slice(-sequence.length);
    if (buffer.length === sequence.length && buffer.every((key, index) => key === sequence[index])) {
      event.preventDefault();
      buffer = [];
      void activate();
    }
  }

  window.addEventListener('keydown', onKeydown);
  return () => window.removeEventListener('keydown', onKeydown);
}
