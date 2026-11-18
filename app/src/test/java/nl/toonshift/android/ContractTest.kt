package nl.toonshift.android

import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun intervalCostRequiresTotalsAndAFlatTariff() {
        val chart = EnergyChart(
            bars = listOf(2.0, 0.0, null), partialBars = listOf(false, false, true),
            unit = "kWh", total = 2.0, partial = true, from = 0, to = 3600,
            estimatedCost = 0.48, unitPrice = 0.24
        )
        assertEquals(0.48, chart.intervalCost(0)!!, 0.000001)
        assertEquals(0.0, chart.intervalCost(1)!!, 0.0)
        assertNull(chart.intervalCost(2))
        assertNull(chart.copy(unit = "W").intervalCost(0))
        assertNull(chart.copy(unitPrice = null).intervalCost(0))
    }

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
