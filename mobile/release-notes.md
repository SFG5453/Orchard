## Orchard Mobile 1.3.0 "Dice Soigne"

### Features
- **Now Playing Transitions**: The player now grows out of the mini player and collapses back into it, with the cover flying between the full-size artwork and the pill's thumbnail as a shared element rather than the two cross-fading.

### Fixes
- **Lyrics Styling**: Mobile lyrics did not match the desktop player. Sung words took the artwork's accent colour instead of staying white with the accent only tinting the sweep, inactive lines were too bright to let the current line stand out, and the type was several sizes small. The list also had no fade at its edges, so lines were sliced off at the top and bottom.
- **Lyrics Colour**: The accent behind sung words was sampled separately from the backdrop, using a square crop of the cover where the backdrop uses the tall one, so the two disagreed about the song's colour. Both now read from the same sample.
- **Pulling The Player Down**: Dragging the player down uncovered black. The player was a navigation destination, which meant the screen behind it had been torn out of the tree and there was nothing left to reveal; it is now presented over the app, so the pull uncovers the real page and the real mini player.
- **Player Stuck Half Closed**: Releasing the pull-down before it committed could leave the player frozen part-way instead of springing back, because each drag update raced the spring-back animation for the same value. The drag no longer competes with the animation.
- **Saved Albums**: The albums shelf listed whatever YouTube Music was surfacing on its library landing page rather than the albums actually saved to the account. Albums now come from the account's own albums tab.
- **Album Subtitles**: Albums saved in Orchard read "Album - 2017 - 2017", because the whole browse line was stored where the artist name belongs and the year was then appended to it again. They now read "Artist - Year", including for albums already saved.
