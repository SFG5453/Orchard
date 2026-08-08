## Orchard Mobile 1.2.0 "Synopses Brusquely"

### Features
- **Autoplay**: When the queue runs dry, Orchard now keeps going with related tracks instead of falling silent. The continuation is pulled from the same radio feed the desktop app uses, appended to the queue as it plays so you can see what's coming and skip past anything you don't want. On by default, with a switch in Settings and a matching one in the queue footer, the way desktop does it, switching it off also clears the tracks it added.

### Fixes
- **Spotify Canvas on Android**: Canvas loops never loaded. The token harvester couldn't complete its handshake in Android's WebView, so every artwork request went out unauthenticated and came back empty. Reworked the harvester and pinned a modern `androidx.webkit`.
- **Crash Scrolling Playlists**: Scrolling a playlist that contained the same track twice crashed the app. The lazy lists keyed their items by track ID, and Compose rejects duplicate keys outright. the affected lists now key by position as well.
- **Update Prompt**: Updates used to download and launch the system installer on their own, which read as an app-wide hijack out of nowhere. Orchard now asks first and shows download progress, and the APK link it hands the installer actually resolves. Debug builds no longer check at all, since the published release can't install over them anyway. Thanks to **Julian-FF2000** for the fix.
- **3-Button Navigation Layout**: The bottom bar applied the system navigation inset a second time on top of the one the app scaffold already added, squashing the tab icons and labels for anyone not using gesture navigation.
