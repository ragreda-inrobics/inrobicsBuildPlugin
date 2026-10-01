import org.junit.Assert.*
import org.junit.Test

class VirtualVersionTest {
    @Test
    fun `Virtual version name contains only the selected date`() {
        val current = InrobicsVersionManager.VersionInfo("42", "1.2.3")
        val manual = InrobicsVersionManager.buildVirtualVersion(current, "2026.10.01", false)
        assertEquals(InrobicsVersionManager.VersionInfo("42", "2026.10.01"), manual)
        val automatic = InrobicsVersionManager.buildVirtualVersion(current, "2026.10.01", true)
        assertEquals(manual?.name, automatic?.name)
        assertEquals(InrobicsVersionManager.computeNextVersionCode("42"), automatic?.code)
        assertNull(InrobicsVersionManager.buildVirtualVersion(current, "2026.02.30", true))
        assertNull(InrobicsVersionManager.buildVirtualVersion(current, "2026.10.01-03", false))
    }

    @Test
    fun `manual patch preserves prefix and Android version code`() {
        val current = InrobicsVersionManager.VersionInfo("2610010", "2026.10.01")
        assertEquals(InrobicsVersionManager.VersionInfo("2610010", "2026.10.12"),
            InrobicsVersionManager.withVirtualPatch(current, "12"))
        assertEquals(InrobicsVersionManager.VersionInfo("42", "1.2.0"),
            InrobicsVersionManager.withVirtualPatch(InrobicsVersionManager.VersionInfo("42", "1.2.3"), "0"))
    }

    @Test
    fun `invalid patches and unsupported version names are rejected`() {
        val current = InrobicsVersionManager.VersionInfo("42", "1.2.3")
        for (patch in listOf("", "-1", "1.2", "abc")) {
            assertNull(InrobicsVersionManager.withVirtualPatch(current, patch))
        }
        assertNull(InrobicsVersionManager.withVirtualPatch(current.copy(name = "1.2.3b"), "4"))
    }
}
