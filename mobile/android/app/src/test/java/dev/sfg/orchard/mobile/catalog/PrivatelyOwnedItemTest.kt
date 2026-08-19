package dev.sfg.orchard.mobile.catalog

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uploads are the one kind of track no guest player client can see, so recognising one from its
 * renderer is what lets playback skip straight to the signed-in player. YouTube never labels them;
 * these are the three markers that only ever appear on privately owned entities.
 *
 * Mirrors the desktop's `musicItemTypes` tests so both clients classify a library the same way.
 */
class PrivatelyOwnedItemTest {

    @Test
    fun recognisesAnUploadFromItsDeleteCommand() {
        val renderer = JSONObject(
            """
            {"menu":{"menuRenderer":{"items":[{"menuNavigationItemRenderer":{"navigationEndpoint":
            {"confirmDialogEndpoint":{"content":{"confirmDialogRenderer":{"confirmButton":
            {"buttonRenderer":{"command":{"musicDeletePrivatelyOwnedEntityCommand":
            {"entityId":"t_po_example"}}}}}}}}}}]}}}
            """.trimIndent(),
        )

        assertTrue(JsonTraversal.isPrivatelyOwned(renderer))
    }

    @Test
    fun recognisesAnUploadFromItsPrivateEntityId() {
        assertTrue(JsonTraversal.isPrivatelyOwned(JSONObject("""{"entityId":"t_po_example"}""")))
        assertTrue(JsonTraversal.isPrivatelyOwned(JSONObject("""{"entity_id":"t_po_example"}""")))
    }

    @Test
    fun recognisesAnUploadFromItsLibraryBrowseId() {
        val renderer = JSONObject(
            """
            {"navigationEndpoint":{"browseEndpoint":
            {"browseId":"FEmusic_library_privately_owned_release_detail_example"}}}
            """.trimIndent(),
        )

        assertTrue(JsonTraversal.isPrivatelyOwned(renderer))
    }

    @Test
    fun doesNotClassifyCatalogTracksAsUploads() {
        val renderer = JSONObject(
            """
            {"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_catalog_release"}},
            "menu":{"menuRenderer":{"items":[{"menuNavigationItemRenderer":
            {"navigationEndpoint":{"browseEndpoint":{"browseId":"UCcatalogArtist"}}}}]}},
            "entityId":"MPTRt_catalog_track"}
            """.trimIndent(),
        )

        assertFalse(JsonTraversal.isPrivatelyOwned(renderer))
    }

    /** A podcast entity id is `_po_`-adjacent without being an upload; the anchor is the guard. */
    @Test
    fun doesNotMistakeAnEntityIdThatMerelyContainsPoForAnUpload() {
        assertFalse(JsonTraversal.isPrivatelyOwned(JSONObject("""{"entityId":"MPSPpo_notanupload"}""")))
        assertFalse(JsonTraversal.isPrivatelyOwned(JSONObject("""{"entityId":"track_something_po_x"}""")))
    }
}
