package cz.aalyrics.domain.lrc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LrcParserTest {

    @Test fun `parses basic LRC with two-digit fractions`() {
        val src = """
            [ti:Demo]
            [ar:Tester]
            [00:00.50]Hello
            [00:02.10]World
        """.trimIndent()
        val r = LrcParser.parse(src)
        assertThat(r.isSynced).isTrue()
        assertThat(r.lines).hasSize(2)
        assertThat(r.lines[0].timestampMs).isEqualTo(500)
        assertThat(r.lines[0].text).isEqualTo("Hello")
        assertThat(r.lines[1].timestampMs).isEqualTo(2100)
    }

    @Test fun `handles three-digit milliseconds`() {
        val r = LrcParser.parse("[01:30.123]Line")
        assertThat(r.lines.single().timestampMs).isEqualTo(90_123)
    }

    @Test fun `multiple timestamps on one line emit duplicates`() {
        val r = LrcParser.parse("[00:01.00][00:05.00]Same")
        assertThat(r.lines.map { it.timestampMs }).containsExactly(1000L, 5000L).inOrder()
        assertThat(r.lines.all { it.text == "Same" }).isTrue()
    }

    @Test fun `applies global offset`() {
        val r = LrcParser.parse("[offset:-500]\n[00:01.00]Hi")
        assertThat(r.lines.single().timestampMs).isEqualTo(500)
    }

    @Test fun `negative-clamped offset never goes below zero`() {
        val r = LrcParser.parse("[offset:-100000]\n[00:01.00]Hi")
        assertThat(r.lines.single().timestampMs).isEqualTo(0)
    }

    @Test fun `falls back to plain text when no timestamps`() {
        val r = LrcParser.parse("Line one\nLine two")
        assertThat(r.isSynced).isFalse()
        assertThat(r.lines.map { it.text }).containsExactly("Line one", "Line two")
    }

    @Test fun `empty input with plain fallback uses fallback`() {
        val r = LrcParser.parse(null, "Fallback line")
        assertThat(r.isSynced).isFalse()
        assertThat(r.lines.single().text).isEqualTo("Fallback line")
    }

    @Test fun `sorts lines by timestamp`() {
        val r = LrcParser.parse("[00:02.00]B\n[00:01.00]A")
        assertThat(r.lines.map { it.text }).containsExactly("A", "B").inOrder()
    }
}
