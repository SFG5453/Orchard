// Adapts renderer like-state requests to the authenticated main-process service.
export function registerYouTubeLikesBridge({ socket, youtubeLikes, bridgeError }) {
  socket.on('music:like:status', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeLikes.status(payload) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('music:like:set', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeLikes.set(payload) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });
}
