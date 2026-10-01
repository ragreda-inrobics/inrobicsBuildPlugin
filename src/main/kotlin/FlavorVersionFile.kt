import java.io.File

/** Finds literal version settings inside the selected product flavor, ignoring comments and nested blocks. */
object FlavorVersionFile {
    private data class Token(val text: String, val start: Int, val end: Int)
    data class Location(val file: File, val codeRange: IntRange, val nameRange: IntRange,
                        val version: InrobicsVersionManager.VersionInfo)

    private fun tokens(source: String): List<Token> {
        val pattern = Regex("""//[^\r\n]*|/\*[\s\S]*?\*/|""" +
            "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[^\\s]")
        return pattern.findAll(source).filterNot { it.value.startsWith("//") || it.value.startsWith("/*") }
            .map { Token(it.value, it.range.first, it.range.last + 1) }.toList()
    }

    private fun closing(tokens: List<Token>, open: Int): Int {
        var depth = 0
        for (i in open until tokens.size) {
            if (tokens[i].text == "{") depth++
            if (tokens[i].text == "}") {
                depth--
                if (depth == 0) return i
            }
        }
        return tokens.size
    }

    fun find(projectPath: String, flavor: String): Location? {
        for (path in listOf("inrobics/build.gradle.kts", "inrobics/build.gradle", "app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")) {
            val file = File(projectPath, path)
            if (!file.exists()) continue
            val source = file.readText()
            val ts = tokens(source)
            for (i in 0 until ts.size - 1) {
                if (ts[i].text != "productFlavors" || ts[i + 1].text != "{") continue
                val end = closing(ts, i + 1)
                var j = i + 2
                while (j < end) {
                    val name = ts[j].text
                    var open = -1
                    if (name.equals(flavor, true) && ts.getOrNull(j + 1)?.text == "{") open = j + 1
                    if (name in listOf("create", "getByName", "named", "maybeCreate") &&
                        ts.getOrNull(j + 1)?.text == "(" && ts.getOrNull(j + 2)?.text?.trim('\'', '"')?.equals(flavor, true) == true &&
                        ts.getOrNull(j + 3)?.text == ")" && ts.getOrNull(j + 4)?.text == "{") open = j + 4
                    if (open >= 0) {
                        val close = closing(ts, open)
                        var code: Token? = null
                        var versionName: Token? = null
                        var k = open + 1
                        while (k < close) {
                            if (ts[k].text == "{") { k = closing(ts, k) + 1; continue }
                            if (ts[k].text in listOf("versionCode", "versionName")) {
                                val valueIndex = k + if (ts.getOrNull(k + 1)?.text == "=") 2 else 1
                                val value = ts.getOrNull(valueIndex)
                                if (ts[k].text == "versionCode") {
                                    require(value != null && Regex("[0-9]+").matches(value.text)) {
                                        "versionCode de $flavor debe ser un número literal en ${file.path}"
                                    }
                                    code = value
                                } else if (value != null && (value.text.startsWith('"') || value.text.startsWith('\''))) versionName = value
                            }
                            k++
                        }
                        if (code == null) return null
                        require(versionName != null) { "Falta versionName literal para $flavor en ${file.path}" }
                        return Location(file, code.start until code.end, versionName.start until versionName.end,
                            InrobicsVersionManager.VersionInfo(code.text, versionName.text.substring(1, versionName.text.length - 1)))
                    }
                    if (ts[j].text == "{") j = closing(ts, j) + 1 else j++
                }
            }
        }
        return null
    }

    fun write(location: Location, version: InrobicsVersionManager.VersionInfo) {
        require(Regex("[0-9]+").matches(version.code)) { "versionCode debe ser numérico" }
        var source = location.file.readText()
        val quote = source[location.nameRange.first]
        val name = quote + version.name.replace("\\", "\\\\").replace(quote.toString(), "\\$quote") + quote
        for ((range, value) in listOf(location.codeRange to version.code, location.nameRange to name).sortedByDescending { it.first.first }) {
            source = source.replaceRange(range, value)
        }
        location.file.writeText(source)
    }
}
