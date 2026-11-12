# dmgbuild settings, run from the repository root:
#   dmgbuild -s packaging/macos/dmg-settings.py -D app=<Orchard.app> Orchard <out.dmg>
# Icon positions and window size match the layout drawn by scripts/macos-artwork.py.
import os.path

app = defines["app"]  # noqa: F821 (provided by dmgbuild)
artwork = "packaging/macos"

format = "UDZO"
filesystem = "HFS+"
files = [app]
symlinks = {"Applications": "/Applications"}
icon = os.path.join(artwork, "Orchard.icns")
background = os.path.join(artwork, "dmg-background.tiff")

window_rect = ((200, 120), (640, 400))
default_view = "icon-view"
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
icon_size = 128
text_size = 13
icon_locations = {os.path.basename(app): (170, 190), "Applications": (470, 190)}
hide_extension = [os.path.basename(app)]
