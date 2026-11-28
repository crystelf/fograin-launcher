package com.fograin.launcher.community

import kotlin.test.Test
import kotlinx.serialization.decodeFromString
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommunityWireTest {
    private val user = """
        {"id":"u1","name":null,"username":"mclhs","host":null,"avatarUrl":"https://lab.crystelf.top/avatar.webp","isBot":false}
    """.trimIndent()

    private val note = """
        {
          "id":"n1",
          "createdAt":"2026-11-01T02:37:37.655Z",
          "text":null,
          "cw":null,
          "user":$user,
          "files":[{
            "id":"f1","name":"clip.mp4","type":"video/mp4",
            "url":"https://lab.crystelf.top/files/clip.mp4",
            "thumbnailUrl":"https://lab.crystelf.top/files/clip.jpg",
            "isSensitive":false,
            "properties":{}
          }],
          "reactions":{},
          "reactionEmojis":{},
          "reactionCount":0,
          "repliesCount":0,
          "renoteCount":0,
          "visibility":"public"
        }
    """.trimIndent()

    @Test
    fun decodesMediaOnlyNotesAndFallbackNames() {
        val decoded = CommunityJson.decodeFromString<CommunityNote>(note)
        assertEquals("n1", decoded.id)
        assertNull(decoded.text)
        assertEquals("mclhs", decoded.user.displayName)
        assertEquals(1, decoded.files.size)
        assertEquals(true, decoded.files.single().isVideo)
    }

    @Test
    fun decodesReplyContextAndTimelineList() {
        val payload = """
            [{
              "id":"n2",
              "createdAt":"2026-10-17T05:00:00.000Z",
              "text":"@mclhs 500M",
              "cw":null,
              "user":{"id":"u2","name":"Jerry","username":"Jerry","host":null,"avatarUrl":"https://lab.crystelf.top/avatar2.webp"},
              "replyId":"n1",
              "reply":$note,
              "files":[],
              "reactions":{"👍":2},
              "reactionEmojis":{},
              "reactionCount":2,
              "repliesCount":0,
              "renoteCount":0,
              "visibility":"public"
            }]
        """.trimIndent()
        val decoded = CommunityJson.decodeFromString<List<CommunityNote>>(payload)
        assertEquals(1, decoded.size)
        assertEquals("Jerry", decoded.single().user.displayName)
        assertNotNull(decoded.single().reply)
        assertEquals(2, decoded.single().reactions["👍"])
    }

    @Test
    fun buildsEncodedMiAuthUrlWithAllPostingScopes() {
        val url = CommunityAuth.url("00000000-0000-4000-8000-000000000000")
        assertEquals(
            "https://lab.crystelf.top/miauth/00000000-0000-4000-8000-000000000000" +
                "?name=Fograin%20Launcher" +
                "&permission=read%3Aaccount%2Cwrite%3Anotes%2Cwrite%3Adrive" +
                "%2Cwrite%3Areactions%2Cwrite%3Avotes%2Cwrite%3Aaccount" +
                "%2Cwrite%3Afollowing%2Cwrite%3Areport-abuse",
            url,
        )
    }

    @Test
    fun decodesMetaPermissionsAndStats() {
        val meta = CommunityJson.decodeFromString<CommunityMeta>(
            """{"name":"Crystelf Lab","version":"2026.10.0","maxNoteTextLength":3000,
                "clientOptions":{"showTimelineForVisitor":true},
                "policies":{"ltlAvailable":true,"gtlAvailable":true,"canPublicNote":true},
                "features":{"miauth":true},"disableRegistration":true}""",
        )
        assertEquals("Crystelf Lab", meta.name)
        assertEquals(true, meta.features.miauth)
        assertEquals(true, meta.clientOptions.showTimelineForVisitor)
        assertEquals(3000, meta.maxNoteTextLength)
    }
    @Test
    fun decodesAdminFlagsAndScheduledNotePolicy() {
        val user = CommunityJson.decodeFromString<CommunityUser>(
            """{"id":"u1","username":"jerry","isAdmin":true,"isModerator":false}""",
        )
        assertEquals(true, user.isAdmin)
        assertEquals(true, user.canModerate)

        val meta = CommunityJson.decodeFromString<CommunityMeta>(
            """{"policies":{"ltlAvailable":true,"gtlAvailable":true,"scheduledNoteLimit":1}}""",
        )
        assertEquals(1, meta.policies.scheduledNoteLimit)
    }

    @Test
    fun decodesDetailedProfileFieldsAndNullFollowing() {
        val user = CommunityJson.decodeFromString<CommunityUser>(
            """{
              "id":"u1","username":"jerry","bannerUrl":"https://example/banner.png",
              "description":"Hello","followersCount":12,"followingCount":3,"notesCount":7,
              "isFollowing":null,"isFollowed":true,
              "fields":[{"name":"Site","value":"example.com"}],
              "createdAt":"2026-10-03T04:13:09.963Z"
            }""",
        )
        assertEquals(false, user.isFollowing)
        assertEquals(true, user.isFollowed)
        assertEquals("https://example/banner.png", user.bannerUrl)
        assertEquals(12, user.followersCount)
        assertEquals("Site", user.fields.single().name)
    }

}
