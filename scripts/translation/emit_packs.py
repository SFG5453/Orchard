#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

# Turns build_packs.py's manifest.json into translation_pack_table.cpp rows and the
# Hugging Face model card. Usage: emit_packs.py <out-dir> <repo-commit-or-main>
import json, os, sys

REPO = "SFG545/orchard-lyric-translation"
out_dir, commit = sys.argv[1], sys.argv[2]
manifest = json.load(open(os.path.join(out_dir, "manifest.json")))

rows = []
for name, pack in manifest.items():
    source = name[:3]
    files = ",\n      ".join(f'{{"{f}", {v["size"]}, "{v["sha256"]}"}}' for f, v in pack["files"].items())
    revision = f'{name}-{pack["revision"][:12]}'
    rows.append(f'''    {{"{name}", "{source}", "{pack["quality"]}", ORCHARD_HOSTED("{name}"), "{revision}",
     {{{files}}},
     "{pack["credit"]}, converted to LiteRT"}},''')
print(f'#define ORCHARD_HOSTED(id) "https://huggingface.co/{REPO}/resolve/{commit}/" id "/"')
print("\n".join(rows))

card = [f"""---
license: other
library_name: litert
pipeline_tag: translation
tags:
  - marian
  - opus-mt
  - lyrics
---

# Orchard lyric translation packs

Int8 LiteRT exports of Marian translation models into English, used by the
Orchard music player to translate song lyrics on device.
Each folder is one pack: `model.tflite` (encode / prefill / decode signatures,
additive `pad_mask`), the source repo's unmodified `source.spm` and `vocab.json`.
Built with Orchard's `scripts/translation/build_packs.py`.

Each pack keeps the license of its source model.

| Pack | Source model (commit) | License | Matches PyTorch greedy |
| --- | --- | --- | --- |"""]
for name, pack in manifest.items():
    lic = pack["credit"].rsplit("(", 1)[-1].rstrip(")")
    card.append(f'| `{name}` | [{pack["source"]}](https://huggingface.co/{pack["source"]}) (`{pack["revision"][:12]}`) | {lic} | {pack["parity"]} |')
card.append("""
The ElanMT pack is CC BY-SA 4.0 (ELAN MITSUA Project / Abstract Engine); its
converted model is shared under the same license. OPUS-MT models are by
Helsinki-NLP (Tiedemann and Thottingal, "OPUS-MT: Building open translation
services for the World", EAMT 2020).
""")
open(os.path.join(out_dir, "README.md"), "w").write("\n".join(card))
