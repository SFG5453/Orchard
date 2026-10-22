import assert from 'node:assert/strict';
import test from 'node:test';

import { cleanNotes, linkNotes, noteTargets } from '../src/notes.js';

test('notes keep inline emphasis and drop other markup', () => {
  assert.equal(cleanNotes('<p><strong>Hi</strong> <em>there</em> <span class="x">you</span></p>\n<p>Next</p>'), '<b>Hi</b> <i>there</i> you<br><br>Next');
});

test('notes link known artists and albums outside tags', () => {
  const targets = noteTargets([
    { artists: ['SZA', 'Travis Scott'], artistBrowseIds: ['am.artists.1', 'am.artists.2'], album: 'SOS', albumId: 'am.albums.9' }
  ]);
  const html = linkNotes('<i>SOS</i> by SZA and Travis Scott, not SZAs.', targets);
  assert.equal(html, '<i><a href="am.albums.9">SOS</a></i> by <a href="am.artists.1">SZA</a> and <a href="am.artists.2">Travis Scott</a>, not SZAs.');
});

test('album cards and artist cards become link targets', () => {
  const targets = noteTargets([], [
    { type: 'album', title: 'Ctrl', browseId: 'am.albums.5' },
    { type: 'artist', title: 'Kendrick Lamar', browseId: 'am.artists.7' }
  ]);
  assert.equal(linkNotes('<i>Ctrl</i> and Kendrick Lamar', targets), '<i><a href="am.albums.5">Ctrl</a></i> and <a href="am.artists.7">Kendrick Lamar</a>');
});
