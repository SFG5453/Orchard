/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { withAlbumVideos } from '../src/catalog/albumVideos.js';

const track = (id, extra = {}) => ({ id, type: 'song', title: id, musicVideoType: 'MUSIC_VIDEO_TYPE_ATV', thumbnail: 'cover', ...extra });

test('video tracks and video shelves share one leading Music Videos shelf', () => {
  const versions = { key: 'other', title: 'Other versions', items: [{ browseId: 'MPREb_x', type: 'album' }] };
  const videos = { key: 'videos', title: 'Videos', items: [{ id: 'omv11111111', type: 'video', title: 'Shelf' }] };
  const tracks = [
    track('atv11111111'),
    track('omv11111111', { musicVideoType: 'MUSIC_VIDEO_TYPE_OMV' }),
    track('ugc22222222', { musicVideoType: 'MUSIC_VIDEO_TYPE_UGC' }),
    track('live3333333', { title: 'Song (Live)' })
  ];
  const sections = withAlbumVideos([versions, videos], tracks);
  assert.deepEqual(sections.map((section) => section.title), ['Music Videos', 'Other versions']);
  assert.deepEqual(sections[0].items.map((item) => item.id), ['omv11111111', 'ugc22222222']);
  assert.ok(sections[0].items.every((item) => item.type === 'video'));
  assert.equal(sections[0].items[1].thumbnail, 'https://i.ytimg.com/vi/ugc22222222/hqdefault.jpg');
});

test('albums without videos keep their shelves as they are', () => {
  const versions = { key: 'other', title: 'Other versions', items: [{ browseId: 'MPREb_x', type: 'album' }] };
  assert.deepEqual(withAlbumVideos([versions], [track('atv11111111')]), [versions]);
});
