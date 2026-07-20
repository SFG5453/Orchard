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
