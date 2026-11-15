package nl.toonshift.android

import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun privateHostsExcludeLoopbackAndNoncanonicalAddresses() {
        listOf("10.0.0.2", "192.168.1.9", "172.31.0.1").forEach { assertTrue(privateAddress(it)) }
        listOf("127.0.0.1", "192.168.1.009", "172.32.0.1", "8.8.8.8",
            "192.168.1.9:8000", "192.168.1.9@example.com").forEach { assertFalse(privateAddress(it)) }
    }
    @Test fun verifierMatchesTheToonHexTokenContract() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(byteArrayOf()))
        val token = newSecret()
        assertTrue(Regex("[0-9a-f]{64}").matches(token))
        assertNotEquals(token, newSecret())
    }
}
