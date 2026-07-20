## Orchard 3.0.2 "Afterglow"

Afterglow restores Orchard's artwork-warp backgrounds and makes playback more resilient across quiet song sections, age restrictions, unavailable tracks, and personal YouTube Music uploads.

### Immersive backgrounds
- Restored the Kawarp-based artwork warp for immersive backgrounds and replaced the previous Pixi renderer.
- Renamed the motion choices to **Animated artwork** and **Artwork warp** so their behavior is clearer.
- Increased the blur and coverage of animated artwork so it blends with the static artwork-warp presentation.

### Smarter crossfade
- Smart Crossfade now distinguishes genuine outros from quiet bridges and breakdowns that return to a sustained chorus or drop.
- Late outro gaps remain valid transition points, avoiding the overly conservative mix-out timing introduced by the first recovery-detection pass.

### More resilient playback
- Manually uploaded YouTube Music songs now retain their account-owned IDs and use the signed-in YouTube Music player context, fixing **“This video is private”** errors.
- Age-gated songs can fall back to a matching music video when their normal audio stream is blocked. Orchard verifies the title, artist, and duration, then keeps the video minimized during audio playback.
- Unavailable, private, or removed tracks are automatically removed from the queue during preload or track advance so playback can continue to the next song.
