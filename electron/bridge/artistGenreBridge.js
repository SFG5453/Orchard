// Registers the renderer-facing artist-genre request on the loopback bridge.
export function registerArtistGenreBridge({ socket, bridgeError, resolveArtistGenre }) {
  socket.on('music:itunes-artist-genre', async (payload, reply) => {
    try {
      reply({ ok: true, data: await resolveArtistGenre(payload) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });
}
