package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test

class JsonObjectFramerTest {
    @Test
    fun splitsConcatenatedObjectsAtEveryPossibleBoundary() {
        val objects = listOf(
            """{"msg_id":257,"param":4}""",
            """{"param":[{"value":"brace } { and quote \" and slash \\"}]}""",
            """{"msg_id":7,"type":"battery","param":"92"}""",
        )
        val wire = objects.joinToString("")
        for (split in 0..wire.length) {
            val framer = JsonObjectFramer()
            assertEquals(objects, framer.feed(wire.take(split)) + framer.feed(wire.drop(split)))
            framer.endOfInput()
        }
    }

    @Test
    fun handlesOneCharacterAtATimeAndWhitespace() {
        val framer = JsonObjectFramer()
        val wire = " \n{\"x\":{\"y\":[{},{}]}}\r\n{} "
        assertEquals(listOf("{\"x\":{\"y\":[{},{}]}}", "{}"), wire.flatMap { framer.feed(it.toString()) })
        framer.endOfInput()
    }

    @Test
    fun rejectsIncompleteInput() {
        val framer = JsonObjectFramer()
        assertTrue(framer.feed("{\"msg_id\":").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { framer.endOfInput() }
    }

    @Test
    fun boundsMemoryAndRejectsNonObjectInput() {
        assertThrows(IllegalArgumentException::class.java) { JsonObjectFramer(4).feed("{\"abc") }
        assertThrows(IllegalArgumentException::class.java) { JsonObjectFramer().feed("garbage{}") }
    }
}
