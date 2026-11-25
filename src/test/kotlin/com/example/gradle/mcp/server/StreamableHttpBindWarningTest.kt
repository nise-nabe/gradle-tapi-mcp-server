package com.example.gradle.mcp.server

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StreamableHttpBindWarningTest {
    @Test
    fun `ipv4 loopback literals do not warn`() {
        isLoopbackBindHost("127.0.0.1") shouldBe true
        isLoopbackBindHost("127.0.1.1") shouldBe true
    }

    @Test
    fun `localhost does not warn`() {
        isLoopbackBindHost("localhost") shouldBe true
    }

    @Test
    fun `ipv6 loopback does not warn`() {
        isLoopbackBindHost("::1") shouldBe true
    }

    @Test
    fun `wildcard binds warn`() {
        isLoopbackBindHost("0.0.0.0") shouldBe false
        isLoopbackBindHost("::") shouldBe false
    }

    @Test
    fun `lan and public addresses warn`() {
        isLoopbackBindHost("192.168.1.10") shouldBe false
        isLoopbackBindHost("10.0.0.5") shouldBe false
    }

    @Test
    fun `unresolvable hosts warn rather than silently pass`() {
        isLoopbackBindHost("no-such-host.invalid") shouldBe false
    }
}
