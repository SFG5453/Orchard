# Orchard JavaScript domain

This directory preserves platform-neutral domain behavior from `orchardv2`:
track-analysis normalization, transition evidence and policy, choreography,
queue primitives, recovery decisions, and stream-quality normalization.

It is not the application runtime and has no Vue, CSS, Electron, Node built-in,
or QML dependency. Rust remains authoritative for PCM analysis and transition
rendering; these modules preserve the higher-level product policy while that
policy receives a versioned native API.

