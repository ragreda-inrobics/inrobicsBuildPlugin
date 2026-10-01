import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class UnityDevVersionsTest {
    private fun metadata(id: String, date: String, flavor: String = "Care"): JsonObject =
        Gson().fromJson("""{"buildId":"$id","createdUtc":"$date","flavor":"$flavor"}""", JsonObject::class.java)

    @Test fun `same build never notifies`() {
        val build = metadata("same", "2026-10-01T10:00:00Z")
        assertFalse(UnityDevVersions.isNewer(build, build, "Care"))
    }
    @Test fun `only a newer dev notifies when installed date exists`() {
        val installed = metadata("stable", "2026-10-01T10:00:00Z")
        assertTrue(UnityDevVersions.isNewer(metadata("new-dev", "2026-10-01T11:00:00Z"), installed, "Care"))
        assertFalse(UnityDevVersions.isNewer(metadata("old-dev", "2026-09-30T10:00:00Z"), installed, "Care"))
    }
    @Test fun `unknown installation can offer a valid dev`() {
        assertTrue(UnityDevVersions.isNewer(metadata("new-dev", "2026-10-01T11:00:00Z"), null, "Care"))
        assertFalse(UnityDevVersions.isNewer(metadata("new-dev", "2026-10-01T11:00:00Z", "Virtual"), null, "Care"))
        assertFalse(UnityDevVersions.isNewer(metadata("../bad", "2026-10-01T11:00:00Z"), null, "Care"))
    }
}
