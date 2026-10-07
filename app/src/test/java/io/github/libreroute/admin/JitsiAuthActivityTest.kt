package io.github.libreroute.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitsiAuthActivityTest {
    @Test
    fun roomUrlIsCanonicalizedWithoutFragmentOrQuery() {
        assertEquals(
            "https://meet.jit.si/team-room",
            JitsiAuthActivity.roomUrlOrNull("https://meet.jit.si/team-room/")
        )
        assertEquals(
            "https://8x8.vc/tenant-id/room-name",
            JitsiAuthActivity.roomUrlOrNull("https://8x8.vc/tenant-id/room-name/")
        )
        assertNull(JitsiAuthActivity.roomUrlOrNull("https://8x8.vc/just-room"))
        assertNull(JitsiAuthActivity.roomUrlOrNull("https://8x8.vc/a/b/c"))
        assertNull(JitsiAuthActivity.roomUrlOrNull("https://meet.jit.si/team-room?x=1"))
        assertNull(JitsiAuthActivity.roomUrlOrNull("http://meet.jit.si/team-room"))
    }

    @Test
    fun tokenIsReadFromJwtFragment() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjQwMDAwMDAwMDB9.signature"
        assertEquals(token, JitsiAuthActivity.tokenFromUrl("https://meet.jit.si/team-room#jwt=$token"))
        assertEquals(token, JitsiAuthActivity.tokenFromUrl("https://8x8.vc/tenant/room?jwt=$token"))
        assertEquals(token, JitsiAuthActivity.tokenFromUrl("https://8x8.vc/tenant/room?token=$token"))
    }
}
