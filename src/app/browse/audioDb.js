export async function fetchArtistArtFromAudioDB(ctx, artistName) {
  if (!artistName) return;

  try {
    const url = `https://www.theaudiodb.com/api/v1/json/2/search.php?s=${encodeURIComponent(artistName)}`;
    const response = await fetch(url);
    if (!response.ok) return;

    const data = await response.json();
    const artist = data?.artists?.[0];

    // Pick the best available wide artwork, preferring fanart and banner
    const highResArtwork = artist?.strArtistFanart || artist?.strArtistFanart2 || artist?.strArtistFanart3 || artist?.strArtistBanner;

    if (highResArtwork) {
      // Ensure we are still viewing the same artist we fetched data for
      if (ctx.browseDetail.value && ctx.browseDetail.value.title === artistName) {
        ctx.browseDetail.value = {
          ...ctx.browseDetail.value,
          customProfileArtwork: highResArtwork
        };
      }
    }
  } catch (error) {
    console.warn(`Failed to fetch high-res artwork from AudioDB for ${artistName}:`, error);
  }
}
