import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class FlavorVersionTest {
    private fun project(test: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("flavor-version-test").toFile()
        try {
            val gradle = File(root, "inrobics/build.gradle").apply { parentFile.mkdirs() }
            val manifest = File(root, "inrobics/src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("""<manifest android:versionCode="42" android:versionName="legacy"/>""")
            }
            test(root, gradle, manifest)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `Groovy edits only selected flavor ignoring nested closures and comments`() = project { root, gradle, manifest ->
        gradle.writeText("""
            android {
                defaultConfig {
                    productFlavors {
                        // virtual { versionCode 999; versionName "wrong" }
                        clinic { versionCode 2606220; versionName "2026.05.18-14" }
                        virtual {
                            if (true) { println("a } brace"); nested { versionCode 999 } }
                            versionCode 2608250
                            versionName '2026.08.25'
                        }
                        care { versionCode 2609011; versionName "2026.09.01" }
                        educa { versionCode 2607270; versionName "2026.07.27" }
                    }
                }
            }
        """.trimIndent())
        val original = gradle.readText()
        val originalManifest = manifest.readText()
        assertEquals("2606220", InrobicsVersionManager.readVersion(root.path, "Clinic")?.code)
        assertEquals("2026.09.01", InrobicsVersionManager.readVersion(root.path, "Care")?.name)
        assertEquals("2607270", InrobicsVersionManager.readVersion(root.path, "Educa")?.code)
        val old = InrobicsVersionManager.readVersion(root.path, "Virtual")!!
        val next = InrobicsVersionManager.VersionInfo("2610010", "2026.10.01")
        InrobicsVersionManager.writeVersion(root.path, old, next, "Virtual")
        assertEquals(original.replace("2608250", "2610010").replace("'2026.08.25'", "'2026.10.01'"), gradle.readText())
        assertEquals(originalManifest, manifest.readText())
        assertEquals(next, InrobicsVersionManager.readVersion(root.path, "Virtual"))
    }

    @Test
    fun `missing flavor version code reads and writes legacy manifest`() = project { root, gradle, manifest ->
        gradle.writeText("""android { productFlavors { virtual { applicationId "com.inrobics.virtual" } } }""")
        val original = gradle.readText()
        val old = InrobicsVersionManager.readVersion(root.path, "Virtual")!!
        assertEquals(InrobicsVersionManager.VersionInfo("42", "legacy"), old)
        val next = InrobicsVersionManager.VersionInfo("43", "2026.10.01")
        InrobicsVersionManager.writeVersion(root.path, old, next, "Virtual")
        assertEquals(next, InrobicsVersionManager.readVersion(root.path, "Virtual"))
        assertEquals(original, gradle.readText())
        assertTrue(manifest.readText().contains("versionCode=\"43\""))
    }

    @Test
    fun `Kotlin DSL assignments preserve other flavors`() = project { root, gradle, _ ->
        gradle.delete()
        val kts = File(gradle.parentFile, "build.gradle.kts")
        kts.writeText("""
            android { productFlavors {
                create("virtual") { versionName = "2026.08.25"; versionCode = 2608250 }
                create("care") { versionCode = 2609011; versionName = "2026.09.01" }
            } }
        """.trimIndent())
        val original = kts.readText()
        val old = InrobicsVersionManager.readVersion(root.path, "Care")!!
        val next = InrobicsVersionManager.VersionInfo("2610011", "2026.10.01")
        InrobicsVersionManager.writeVersion(root.path, old, next, "Care")
        assertEquals(original.replace("2609011", "2610011").replace("2026.09.01", "2026.10.01"), kts.readText())
        assertEquals("2608250", InrobicsVersionManager.readVersion(root.path, "Virtual")?.code)
    }
}
