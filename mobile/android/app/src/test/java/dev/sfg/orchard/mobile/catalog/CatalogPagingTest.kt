package dev.sfg.orchard.mobile.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Mixed for you" entries are radios: their continuations never run out, so a finite playlist's
 * page budget means every open pages to the cap before the listener sees a full list.
 */
class CatalogPagingTest {

    @Test
    fun mixesAndRadiosPageFarLessThanSavedPlaylists() {
        assertEquals(CatalogRepository.MAX_MIX_PAGES, CatalogRepository.pageBudget("RDCLAK5uy_examplemix"))
        assertEquals(CatalogRepository.MAX_MIX_PAGES, CatalogRepository.pageBudget("VLRDTMAK5uy_supermix"))
        assertEquals(CatalogRepository.MAX_MIX_PAGES, CatalogRepository.pageBudget("RDAMVMexamplevideo"))
    }

    @Test
    fun finiteCollectionsKeepTheFullBudget() {
        assertEquals(CatalogRepository.MAX_TRACK_PAGES, CatalogRepository.pageBudget("VLPLexampleplaylist"))
        assertEquals(CatalogRepository.MAX_TRACK_PAGES, CatalogRepository.pageBudget("PLexampleplaylist"))
        assertEquals(CatalogRepository.MAX_TRACK_PAGES, CatalogRepository.pageBudget("MPREb_examplealbum"))
        assertEquals(CatalogRepository.MAX_TRACK_PAGES, CatalogRepository.pageBudget("FEmusic_liked_videos"))
        // A saved playlist whose id merely opens with the prefix letters must not be mistaken for
        // a radio once the VL prefix is gone.
        assertEquals(CatalogRepository.MAX_TRACK_PAGES, CatalogRepository.pageBudget("VLPLRDsomething"))
    }
}
