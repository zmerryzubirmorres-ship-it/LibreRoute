package io.github.libreroute.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ZapretProfileTest {
    private val custom = ZapretProfile("custom", "Мой оператор", split = "midsld", methodEol = false)

    @Test fun upgradeKeepsTheExistingTpwsCommand() {
        assertEquals(listOf("tpws", "--socks", "--bind-addr=127.0.0.1", "--port=1080", "--maxconn=1024",
            "--filter-tcp=80,443", "--split-pos=1,midsld", "--tlsrec=sni", "--methodeol"),
            ZapretProfileState().active.command("tpws", 1080))
        assertTrue(ZapretProfileState().active.blockQuic)
    }

    @Test fun selectedProfileSurvivesSerializationAndCanBeRolledBack() {
        val selected = ZapretProfileState().save(custom).select(custom.id)
        val restored = Json.decodeFromString<ZapretProfileState>(Json.encodeToString(selected))
        restored.validate()
        assertEquals(custom, restored.active)
        assertEquals(ZapretProfile.DEFAULT, restored.rollback().active)
        assertEquals(custom, restored.rollback().rollback().active)
    }

    @Test fun editingActiveProfileRetainsItsPreviousSettings() {
        val before = ZapretProfileState().save(custom).select(custom.id)
        val edited = custom.copy(split = "1", tlsRecord = false, blockQuic = false)
        val after = before.save(edited)
        assertEquals(edited, after.active)
        assertEquals(custom, after.rollback().active)
        assertEquals(edited, after.rollback().rollback().active)
        // Editing another profile and selecting the same one must not lose rollback.
        assertEquals(custom, after.save(custom.copy(id = "another")).select(custom.id).previous)
    }

    @Test fun deletionCannotRecreateAProfileOrRemoveActiveOrStandard() {
        val state = ZapretProfileState().save(custom).select(custom.id).select("standard")
        val removed = state.delete(custom.id)
        assertNull(removed.previous)
        assertEquals(removed, removed.rollback())
        assertThrows(IllegalArgumentException::class.java) { state.delete("standard") }
        assertThrows(IllegalArgumentException::class.java) { state.select(custom.id).delete(custom.id) }
        assertThrows(IllegalArgumentException::class.java) { state.save(ZapretProfile.DEFAULT.copy(name = "changed")) }
    }

    @Test fun invalidImportsAndArbitraryArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { custom.copy(split = "1 --bind-addr=0.0.0.0").command("tpws", 1080) }
        assertThrows(IllegalArgumentException::class.java) { custom.copy(name = "\n").validate() }
        assertThrows(IllegalArgumentException::class.java) { ZapretProfileState(profiles = listOf(custom), activeId = custom.id).validate() }
        assertThrows(IllegalArgumentException::class.java) { ZapretProfileState(activeId = "missing").validate() }
        assertThrows(IllegalArgumentException::class.java) { ZapretProfileState(profiles = listOf(ZapretProfile.DEFAULT, custom, custom)).validate() }
        assertThrows(IllegalArgumentException::class.java) { ZapretProfileState(previous = custom).validate() }
        val full = (1..31).fold(ZapretProfileState()) { state, i -> state.save(custom.copy(id = "profile-$i")) }
        assertThrows(IllegalArgumentException::class.java) { full.save(custom) }
    }

    @Test fun disorderIsNotSentWithoutSplitting() {
        val profile = custom.copy(split = "", disorder = true, hostCase = true, tlsRecord = false, methodEol = false)
        val command = profile.command("tpws", 1080)
        assertFalse(command.any { it.startsWith("--split-pos") || it == "--disorder" })
        assertFalse(command.any { it.startsWith("--tlsrec") || it == "--methodeol" })
        assertTrue(command.contains("--hostcase"))
    }
}
