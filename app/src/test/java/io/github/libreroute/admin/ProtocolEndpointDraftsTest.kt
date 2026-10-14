package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class ProtocolEndpointDraftsTest {
    @Test fun restorationFillsAnInitiallyEmptyScreen() {
        val drafts = ProtocolEndpointDrafts()
        assertEquals("", drafts.value("jazz", null, ""))
        assertEquals("https://example.test/restored", drafts.value("jazz", null, "https://example.test/restored"))
        assertTrue(drafts.savedEdits().isEmpty())
    }

    @Test fun untouchedDefaultsFollowTheLatestServerSnapshot() {
        val drafts = ProtocolEndpointDrafts()
        assertEquals("old", drafts.value("vyandex", "old"))
        assertEquals("current", drafts.value("vyandex", "current"))
    }

    @Test fun rotationBeforeRestorationDoesNotSaveEmptyDefaultsAsEdits() {
        val first = ProtocolEndpointDrafts()
        first.value("vyandex", "")
        val recreated = recreate(first)
        assertEquals("restored", recreated.value("vyandex", "restored"))
    }

    @Test fun userEditsIncludingAnIntentionallyEmptyFieldSurviveRotationAndRefresh() {
        val first = ProtocolEndpointDrafts()
        first.edit("jazz", "my-room")
        first.edit("vyandex", "")
        val recreated = recreate(first)
        assertEquals("my-room", recreated.value("jazz", "server-room"))
        assertEquals("", recreated.value("vyandex", "server-document"))
        assertEquals("other-room", recreated.value("jitsi", "other-room"))
    }

    private fun recreate(previous: ProtocolEndpointDrafts) = ProtocolEndpointDrafts().apply {
        previous.savedEdits().forEach { (id, value) -> edit(id, value) }
    }
}
