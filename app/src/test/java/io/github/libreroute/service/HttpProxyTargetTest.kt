package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test

class HttpProxyTargetTest {
    @Test fun canonicalHostAndOriginalPathWithoutDns() {
        assertEquals(HttpProxyTarget("xn--e1afmkfd.xn--p1ai", 80, "/p?q=1"),
            parseHttpProxyTarget("GET", "http://пример.рф/p?q=1", listOf("xn--e1afmkfd.xn--p1ai:80")))
        assertEquals(HttpProxyTarget("example.org", 443, ""),
            parseHttpProxyTarget("CONNECT", "EXAMPLE.ORG.:443", listOf("example.org:443")))
        assertEquals(HttpProxyTarget("192.0.2.1", 8080, "/"), parseHttpProxyTarget("GET", "/", listOf("192.0.2.1:8080")))
    }
    @Test fun ambiguousOrInvalidDestinationsFailBeforeUpstream() {
        for ((method,target,hosts) in listOf(
            Triple("GET", "http://example.org/", listOf("other.example")),
            Triple("GET", "http://example.org/", listOf("example.org", "example.org")),
            Triple("GET", "http://user:secret@example.org/", emptyList()),
            Triple("GET", "http://example.org/#fragment", emptyList()),
            Triple("CONNECT", "example.org:443/path", emptyList()),
            Triple("CONNECT", "example.org", emptyList()),
            Triple("CONNECT", "example.org:0", emptyList()),
            Triple("CONNECT", "[::1]:443", emptyList()),
            Triple("GET", "//example.org/", listOf("example.org"))
        )) assertNull("$method $target", parseHttpProxyTarget(method,target,hosts))
    }
}
