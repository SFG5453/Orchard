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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParserTest {
    @Test
    fun searchNormalizesTrackAndAlbumRenderers() {
        val root = JSONObject(
            """{
              "contents": [
                {"musicResponsiveListItemRenderer": {
                  "flexColumns": [
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                      {"text": "Night Drive", "navigationEndpoint": {"watchEndpoint": {"videoId": "video-1"}}}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Ky. • Late Nights • 3:24"}]}}}
                  ],
                  "thumbnail": {"thumbnails": [{"url": "https://img/track", "width": 320, "height": 320}]}
                }},
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "Late Nights", "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "MPREalbum", "browseEndpointContextSupportedConfigs": {
                      "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}
                    }
                  }}}]},
                  "subtitle": {"runs": [{"text": "Ky."}]},
                  "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                    {"url": "https://img/album", "width": 640, "height": 640}
                  ]}}}
                }}
              ]
            }""",
        )

        val results = CatalogParser.search(root)

        assertEquals(1, results.tracks.size)
        assertEquals("video-1", results.tracks.single().id)
        assertEquals("Ky.", results.tracks.single().artist)
        assertEquals(204_000, results.tracks.single().durationMs)
        assertEquals(1, results.albums.size)
        assertEquals("MPREalbum", results.albums.single().id)
        assertFalse(results.isEmpty)
    }

    @Test
    fun artistAlbumCardPrefersItsBrowseActionOverAPlayableTitle() {
        val root = JSONObject(
            """{
              "header": {"musicImmersiveHeaderRenderer": {"title": {"runs": [{"text": "Ky."}]}}},
              "contents": [{"musicCarouselShelfRenderer": {
                "header": {"musicCarouselShelfBasicHeaderRenderer": {
                  "title": {"runs": [{"text": "Albums"}]}
                }},
                "contents": [{"musicTwoRowItemRenderer": {
                  "title": {"runs": [{
                    "text": "Late Nights",
                    "navigationEndpoint": {"watchEndpoint": {"videoId": "preview-video"}}
                  }]},
                  "subtitle": {"runs": [{"text": "Album • Ky. • 2026"}]},
                  "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "MPRElate-nights",
                    "browseEndpointContextSupportedConfigs": {
                      "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}
                    }
                  }}
                }}]
              }}]
            }""".trimIndent(),
        )

        val detail = CatalogParser.detail("UCartist", root)
        val album = detail.sections.single().items.single()

        assertTrue(album is CatalogItem.Record)
        assertEquals("MPRElate-nights", album.stableId)
    }

    @Test
    fun searchKeepsOfficialArtistChannelsAndDropsUserChannels() {
        fun channel(name: String, browseId: String, pageType: String) = """
            {"musicTwoRowItemRenderer": {
              "title": {"runs": [{"text": "$name", "navigationEndpoint": {"browseEndpoint": {
                "browseId": "$browseId", "browseEndpointContextSupportedConfigs": {
                  "browseEndpointContextMusicConfig": {"pageType": "$pageType"}
                }
              }}}]},
              "subtitle": {"runs": [{"text": "Artist"}]}
            }}
        """

        val root = JSONObject(
            """{"contents": [
              ${channel("Ky.", "UCartist", "MUSIC_PAGE_TYPE_ARTIST")},
              ${channel("Ky. Fan Uploads", "UCchannel", "MUSIC_PAGE_TYPE_USER_CHANNEL")}
            ]}""",
        )

        val results = CatalogParser.search(root)

        assertEquals(listOf("UCartist"), results.artists.map { it.id })
        assertTrue(results.playlists.isEmpty())
    }

    @Test
    fun homeFallsBackToOneRealSectionWhenCarouselShapeChanges() {
        val root = JSONObject(
            """{"contents": [{"musicTwoRowItemRenderer": {
              "title": {"runs": [{"text": "Artist", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist"}}}]},
              "subtitle": {"runs": [{"text": "2M listeners"}]}
            }}]}""",
        )

        val sections = CatalogParser.home(root)

        assertEquals(1, sections.size)
        assertEquals(1, sections.single().items.filterIsInstance<CatalogItem.Performer>().size)
    }

    @Test
    fun homeKeepsCarouselSongShelfAndGridSectionsInOrder() {
        fun song(id: String, title: String) = """
            {"musicResponsiveListItemRenderer": {
              "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{
                "text": "$title", "navigationEndpoint": {"watchEndpoint": {"videoId": "$id"}}
              }]}}}]
            }}
        """.trimIndent()
        fun album(id: String, title: String) = """
            {"musicTwoRowItemRenderer": {
              "title": {"runs": [{"text": "$title"}]},
              "navigationEndpoint": {"browseEndpoint": {
                "browseId": "$id", "browseEndpointContextSupportedConfigs": {
                  "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}
                }
              }}
            }}
        """.trimIndent()
        val root = JSONObject(
            """{
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {
                "content": {"sectionListRenderer": {"contents": [
                  {"musicCarouselShelfRenderer": {
                    "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Listen again"}]}}},
                    "contents": [${album("MPREagain", "Again")}]
                  }},
                  {"musicShelfRenderer": {
                    "title": {"runs": [{"text": "Quick picks"}]},
                    "contents": [${song("quick-song", "Quick song")}]
                  }},
                  {"gridRenderer": {
                    "header": {"gridHeaderRenderer": {"title": {"runs": [{"text": "Albums for you"}]}}},
                    "items": [${album("MPREalbum", "An album")}]
                  }}
                ], "continuations": [{"nextContinuationData": {"continuation": "home-page-2"}}]}}
              }}]}}
            }""".trimIndent(),
        )

        val sections = CatalogParser.home(root)

        assertEquals(listOf("Listen again", "Quick picks", "Albums for you"), sections.map { it.title })
        assertTrue(sections[1].items.single() is CatalogItem.Song)
        assertEquals("home-page-2", CatalogParser.homeContinuationToken(root))
    }

    @Test
    fun savedPlaylistGridExposesItemsAndContinuation() {
        val root = JSONObject(
            """{
              "gridRenderer": {
                "items": [{"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "Road trip"}]},
                  "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "VLroad-trip", "browseEndpointContextSupportedConfigs": {
                      "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_PLAYLIST"}
                    }
                  }}
                }}],
                "continuations": [{"nextContinuationData": {"continuation": "playlist-page-2"}}]
              }
            }""".trimIndent(),
        )

        val items = CatalogParser.sectionItems(root)

        assertEquals(listOf("Road trip"), items.map { it.title })
        assertTrue(items.single() is CatalogItem.Collection)
        assertEquals("playlist-page-2", CatalogParser.continuationToken(root))
    }

    @Test
    fun playlistDetailUsesResponsiveHeaderAndDoesNotTreatPlayCountAsAlbum() {
        val root = JSONObject(
            """{
              "header": {"musicEditablePlaylistDetailHeaderRenderer": {"header": {
                "musicResponsiveHeaderRenderer": {
                  "title": {"runs": [{"text": "Memphis rotation"}]},
                  "straplineText": {"runs": [{"text": "SFG"}]},
                  "thumbnail": {"thumbnails": [{
                    "url": "https://lh3.googleusercontent.com/example=w60-h60-l90-rj",
                    "width": 60, "height": 60
                  }]}
                }
              }}},
              "contents": [{"musicResponsiveListItemRenderer": {
                "flexColumns": [
                  {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{
                    "text": "Projects", "navigationEndpoint": {"watchEndpoint": {"videoId": "project-id"}}
                  }]}}},
                  {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{
                    "text": "Song • Moneybagg Yo • 12M plays • 2:48"
                  }]}}}
                ]
              }}]
            }""",
        )

        val detail = CatalogParser.detail("VLplaylist", root)

        assertEquals("Memphis rotation", detail.title)
        assertEquals("SFG", detail.subtitle)
        assertEquals("https://lh3.googleusercontent.com/example=w540-h540-l90-rj", detail.artworkUrl)
        assertEquals("Moneybagg Yo", detail.tracks.single().artist)
        assertEquals("", detail.tracks.single().album)
        assertEquals(true, detail.editable)
    }

    @Test
    fun playlistAuthorFiltersVisibilityLabels() {
        val root = JSONObject(
            """{
              "contents": [
                {"musicTwoRowItemRenderer": {
                  "title": {"runs": [{"text": "My Playlist"}]},
                  "subtitle": {"runs": [{"text": "Unlisted • 50 songs"}]},
                  "navigationEndpoint": {"browseEndpoint": {"browseId": "VLplaylist123"}}
                }}
              ]
            }""",
        )

        val results = CatalogParser.search(root)
        assertEquals(1, results.playlists.size)
        assertEquals("YouTube Music", results.playlists.single().author)
    }

    @Test
    fun cardShelfNormalizesArtistAndTopTracks() {
        val root = JSONObject(
            """{
              "contents": [
                {"musicCardShelfRenderer": {
                  "title": {"runs": [{"text": "USHER", "navigationEndpoint": {"browseEndpoint": {
                    "browseId": "UCILuIcqzJMtkxCmftNVjNBQ",
                    "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}}
                  }}}]},
                  "subtitle": {"runs": [{"text": "Artist • 79.4M monthly audience"}]},
                  "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://img/usher"}]}}},
                  "contents": [
                    {"musicResponsiveListItemRenderer": {
                      "flexColumns": [
                        {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "U Got It Bad", "navigationEndpoint": {"watchEndpoint": {"videoId": "vid-1"}}}]}}},
                        {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Song • 4:08"}]}}}
                      ]
                    }}
                  ]
                }}
              ]
            }""",
        )

        val results = CatalogParser.search(root)

        assertEquals(1, results.artists.size)
        assertEquals("USHER", results.artists.single().name)
        assertEquals("UCILuIcqzJMtkxCmftNVjNBQ", results.artists.single().id)
        assertEquals(1, results.tracks.size)
        assertEquals("U Got It Bad", results.tracks.single().title)
        assertEquals("USHER", results.tracks.single().artist)
        assertEquals(248_000, results.tracks.single().durationMs)
    }

    @Test
    fun testArtistDetailParsing() {
        val json = JSONObject(
            """
            {
              "header": {
                "musicImmersiveHeaderRenderer": {
                  "title": { "runs": [{ "text": "USHER" }] },
                  "description": { "runs": [{ "text": "Usher Raymond IV is an American singer..." }] },
                  "subscriptionButton": {
                    "subscribeButtonRenderer": {
                      "subscriberCountText": { "runs": [{ "text": "79.4M subscribers" }] }
                    }
                  },
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [{ "url": "https://lh3.googleusercontent.com/usher.jpg", "width": 540, "height": 540 }]
                      }
                    }
                  }
                }
              },
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "title": { "runs": [{ "text": "Top songs" }] },
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Yeah!",
                                                    "navigationEndpoint": {
                                                      "watchEndpoint": { "videoId": "GxBSyx85FW8" }
                                                    }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "runs": [{ "text": "USHER • 2004" }] }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicCarouselShelfRenderer": {
                                  "header": {
                                    "musicCarouselShelfBasicHeaderRenderer": {
                                      "title": { "runs": [{ "text": "Albums" }] }
                                    }
                                  },
                                  "contents": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "runs": [{ "text": "Confessions" }] },
                                        "subtitle": { "runs": [{ "text": "Album • 2004" }] },
                                        "navigationEndpoint": {
                                          "browseEndpoint": { "browseId": "MPREb_album123" }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val detail = CatalogParser.detail("UCILuIcqzJMtkxCmftNVjNBQ", json)
        assertEquals("USHER", detail.title)
        assertEquals(CatalogKind.ARTIST, detail.kind)
        assertEquals("79.4M subscribers", detail.subtitle)
        assertEquals("Usher Raymond IV is an American singer...", detail.description)
        assertEquals("https://lh3.googleusercontent.com/usher.jpg", detail.artworkUrl)
        assertEquals(1, detail.tracks.size)
        assertEquals("Yeah!", detail.tracks.first().title)
        assertEquals("GxBSyx85FW8", detail.tracks.first().id)
        assertEquals(1, detail.sections.size)
        assertEquals("Albums", detail.sections.first().title)
        assertEquals(1, detail.sections.first().items.size)
        assertEquals("Confessions", detail.sections.first().items.first().title)
    }

    @Test
    fun testLiveSearchCardShelves() {
        val client = InnerTubeClient(okhttp3.OkHttpClient())
        val json = client.search("usher")
        val results = CatalogParser.search(json)
        assertEquals("USHER", results.artists.first().name)
        assertTrue(results.albums.isNotEmpty())
        assertTrue(results.albums.any { it.artist.contains("Usher", ignoreCase = true) })

        val artistJson = client.browse("UCILuIcqzJMtkxCmftNVjNBQ")
        val detail = CatalogParser.detail("UCILuIcqzJMtkxCmftNVjNBQ", artistJson)
        assertEquals("USHER", detail.title)
        assertEquals(CatalogKind.ARTIST, detail.kind)
        assertTrue(detail.tracks.isNotEmpty())
        assertTrue(detail.sections.isNotEmpty())
        assertTrue(detail.sections.any { it.title.contains("Album", true) })
    }

    @Test
    fun albumDetailDoesNotTreatYearAsArtist() {
        val root = JSONObject(
            """{
              "header": {
                "musicResponsiveHeaderRenderer": {
                  "title": {"runs": [{"text": "Confessions (Expanded Edition)"}]},
                  "subtitle": {"runs": [{"text": "Album"}, {"text": " • "}, {"text": "2004"}]},
                  "straplineText": {"runs": [{"text": "Usher", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCILuIcqzJMtkxCmftNVjNBQ"}}}]}
                }
              },
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "title": {"runs": [{"text": "Tracks"}]},
                              "contents": [{
                                "musicResponsiveListItemRenderer": {
                                  "flexColumns": [
                                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                                      {"text": "Yeah! (feat. Lil Jon & Ludacris)", "navigationEndpoint": {"watchEndpoint": {"videoId": "GxBSyx85FW8"}}}
                                    ]}}},
                                    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                                      {"text": "2004"}, {"text": " • "}, {"text": "4:10"}
                                    ]}}}
                                  ]
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }"""
        )

        val detail = CatalogParser.detail("MPREb_album", root)
        assertEquals("Confessions (Expanded Edition)", detail.title)
        assertEquals(1, detail.tracks.size)
        assertEquals("Yeah! (feat. Lil Jon & Ludacris)", detail.tracks.first().title)
        assertEquals("Usher", detail.tracks.first().artist)
        assertFalse(detail.tracks.first().artist == "2004")
    }

    @Test
    fun parsesExplicitTracksWithMusicExplicitBadge() {
        val root = JSONObject(
            """{
              "contents": {
                "musicShelfRenderer": {
                  "contents": [
                    {"musicResponsiveListItemRenderer": {
                      "flexColumns": [
                        {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                          {"text": "Explicit Song", "navigationEndpoint": {"watchEndpoint": {"videoId": "exp-1"}}}
                        ]}}},
                        {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Artist • Album • 3:00"}]}}}
                      ],
                      "badges": [
                        {"musicInlineBadgeRenderer": {
                          "icon": {"iconType": "MUSIC_EXPLICIT_BADGE"},
                          "accessibilityData": {"accessibilityData": {"label": "Explicit"}}
                        }}
                      ]
                    }}
                  ]
                }
              }
            }"""
        )

        val results = CatalogParser.search(root)
        assertEquals(1, results.tracks.size)
        val track = results.tracks.first()
        assertEquals("exp-1", track.id)
        assertTrue(track.explicit)
    }
}
