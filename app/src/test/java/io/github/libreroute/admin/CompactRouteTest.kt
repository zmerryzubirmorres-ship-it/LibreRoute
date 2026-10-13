package io.github.libreroute.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompactRouteTest {
    @Test fun rejectsNonCompactOrOversizedStrings() {
        assertThrows(IllegalArgumentException::class.java) { CompactRoute.parse("libreroute://invitation?bootstrap=x") }
        assertThrows(IllegalArgumentException::class.java) {
            CompactRoute.toLink("x".repeat(3001))
        }
    }

    @Test fun preservesCompactPrefixAndPayload() {
        val link = CompactRoute.toLink("abc_DEF-123")
        assertEquals("libreroute://invitation?route=abc_DEF-123", link)
    }
}
