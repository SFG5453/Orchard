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
import { isUploadedMusicItem } from '../electron/catalog/musicItemTypes.js';

test('identifies uploaded music from its private artist browse ID', () => {
  assert.equal(isUploadedMusicItem({
    artistBrowseIds: ['FEmusic_library_privately_owned_artist_detail_example']
  }), true);
});

test('identifies uploaded music from its private release browse ID', () => {
  assert.equal(isUploadedMusicItem({
    albumId: 'FEmusic_library_privately_owned_release_detail_example'
  }), true);
});

test('identifies a metadata-free manual upload from its delete command', () => {
  assert.equal(isUploadedMusicItem({
    menu: {
      menuRenderer: {
        items: [{
          menuNavigationItemRenderer: {
            navigationEndpoint: {
              confirmDialogEndpoint: {
                content: {
                  confirmDialogRenderer: {
                    confirmButton: {
                      buttonRenderer: {
                        command: {
                          musicDeletePrivatelyOwnedEntityCommand: {
                            entityId: 't_po_example'
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }]
      }
    }
  }), true);
});

test('identifies an upload from its parsed private entity ID', () => {
  assert.equal(isUploadedMusicItem({ entity_id: 't_po_example' }), true);
});

test('does not classify catalog tracks as uploads', () => {
  assert.equal(isUploadedMusicItem({
    artistBrowseIds: ['UCcatalogArtist'],
    albumId: 'MPREb_catalog_release'
  }), false);
});
