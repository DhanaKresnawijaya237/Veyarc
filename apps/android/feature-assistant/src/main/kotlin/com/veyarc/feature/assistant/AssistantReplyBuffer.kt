package com.veyarc.feature.assistant

/** Read only the root JSON text string. Candidates and binding fields stay private until validation. */
internal class AssistantReplyBuffer(private val request: AssistantRequest) {
    private var depth = 0
    private var inString = false
    private var keyExpected = false
    private var readingKey = false
    private var readingReply = false
    private var replySeen = false
    private var key = ""
    private val token = StringBuilder()
    private val reply = StringBuilder()
    private var escaped = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var lastPublishedAt = 0L

    fun append(piece: String) {
        for (char in piece) {
            if (!inString) {
                when (char) {
                    '{', '[' -> {
                        depth++
                        if (depth == 1) keyExpected = true
                    }

                    '}', ']' -> depth--

                    ',' -> if (depth == 1) keyExpected = true

                    ':' -> if (depth == 1) keyExpected = false

                    '"' -> {
                        inString = true
                        readingKey = depth == 1 && keyExpected
                        readingReply = depth == 1 && !keyExpected && key == "text"
                        if (readingReply) {
                            require(!replySeen)
                            replySeen = true
                        }
                        token.clear()
                    }
                }
            } else if (unicodeDigits > 0) {
                unicodeValue = unicodeValue * 16 + (char.digitToIntOrNull(16) ?: error("Invalid JSON escape"))
                unicodeDigits--
                if (unicodeDigits == 0) appendCharacter(unicodeValue.toChar())
            } else if (escaped) {
                escaped = false
                if (char == 'u') {
                    unicodeDigits = 4
                    unicodeValue = 0
                } else {
                    appendCharacter(
                        when (char) {
                            '"', '\\', '/' -> char
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> error("Invalid JSON escape")
                        },
                    )
                }
            } else {
                when (char) {
                    '\\' -> escaped = true

                    '"' -> {
                        inString = false
                        if (readingKey) key = token.toString()
                        if (readingReply) publish(force = true)
                        readingReply = false
                    }

                    else -> {
                        require(char >= ' ')
                        appendCharacter(char)
                    }
                }
            }
        }
        publish()
    }

    private fun appendCharacter(char: Char) {
        if (readingKey) token.append(char)
        if (readingReply) reply.append(char)
    }

    fun publish(force: Boolean = false) {
        if (reply.isEmpty()) return
        val now = System.nanoTime()
        if (!force && lastPublishedAt != 0L && now - lastPublishedAt < 100_000_000L) return
        val length = reply.length - if (reply.last().isHighSurrogate()) 1 else 0
        if (length == 0) return
        val text = reply.substring(0, length)
        if (text != request.latestReply) request.reportReply(text)
        lastPublishedAt = now
    }
}
