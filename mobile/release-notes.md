## Orchard Mobile 1.7.1 "Covenant Footy"

### Fixed
- **Playback Against Rationed Guest Clients**: Orchard now mints a WebPO proof of origin in a WebView and declares it in the web-family player request, ordering attested clients first; a refused proof is invalidated and its client blacklisted so retries rotate families. Playback and downloads also send explicit bounded ranges, instead of relying on unbounded requests that were answered at a trickle and cut short.
- **Uploads**: Tracks the catalog flags as privately owned uploads skip the guest client chain and resolve through the signed-in web player, so they report "sign in" rather than "Video unavailable" (basically, they play).
