## Orchard Mobile 1.4.0 "Martyrs Schoolboy"

### Improvements
- **Smarter Transition Timing**: Best Mix now analyzes more of each song's audible opening and ending, calibrates beat and downbeat timing more carefully, and chooses safer mix windows. Transitions stay near musical boundaries without cutting off the outgoing track, starting the next song too late, or forcing an aggressive stretch when the songs are not a good match.
- **Cleaner Musical Handoffs**: DJ-style transitions now control bass and upper frequencies independently. The outgoing bass makes room for the incoming track while vocals and higher-frequency detail remain smooth, producing a firmer handoff without an abrupt volume dip.
- **Transition-Aware Player**: During an overlap, the player now follows the incoming song at the actual handoff instead of showing stale artwork, titles, timing, or progress from the outgoing track. The Now Playing overlay, compact controls, scrubber, phone layout, and tablet layout all use the same transition state.

### Fixes
- **Smart Transitions Restored**: Repaired and recalibrated transition preparation, decoding, analysis, planning, and WSOLA timing so Best Mix can reliably prepare the next track and fall back cleanly when a full beat-matched transition is not suitable.
- **Duplicate Autoplay Songs**: Autoplay no longer queues the same recording several times under different YouTube video IDs. Album audio, official videos, radio edits, and collaborator byline variants are recognized as the same song, with album audio preferred; covers and substantially different versions remain separate recommendations.
