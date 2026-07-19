// Adapts renderer history events to the authenticated main-process history service.
export function registerYouTubeHistoryBridge({ socket, youtubeHistory }) {
  socket.on('music:history:start', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeHistory.start(payload || {}) });
    } catch (error) {
      console.warn(`Could not add YouTube history: ${error.message}`);
      reply({ ok: true, data: { recorded: false } });
    }
  });

  socket.on('music:history:update', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeHistory.update(payload || {}) });
    } catch (error) {
      console.warn(`Could not update YouTube history: ${error.message}`);
      reply({ ok: true, data: { recorded: false } });
    }
  });
}
