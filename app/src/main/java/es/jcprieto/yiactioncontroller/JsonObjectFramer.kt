package es.jcprieto.yiactioncontroller

/** Stateful framing across arbitrary TCP chunks; braces inside strings are not delimiters. */
internal class JsonObjectFramer(private val maxObjectChars: Int = 1_048_576) {
    private val buffer = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun feed(chunk: CharSequence): List<String> = buildList {
        for (char in chunk) {
            if (buffer.isEmpty()) {
                if (char.isWhitespace()) continue
                require(char == '{') { "Se esperaba un objeto JSON" }
            }
            buffer.append(char)
            require(buffer.length <= maxObjectChars) { "Objeto JSON demasiado grande" }
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth == 0) {
                    add(buffer.toString())
                    buffer.setLength(0)
                }
            }
        }
    }

    fun endOfInput() {
        require(buffer.isEmpty()) { "La cámara cerró el socket con un JSON incompleto" }
    }
}
