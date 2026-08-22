#!/bin/sh

config_root=${XDG_CONFIG_HOME:-"$HOME/.config"}
installed_orchard="$config_root/orchard/versions/5.0.0/orchard"

if [ -x "$installed_orchard" ]; then
  exec "$installed_orchard" "$@"
fi

exec /usr/lib/orchard-packages/orchard-packages "$@"
