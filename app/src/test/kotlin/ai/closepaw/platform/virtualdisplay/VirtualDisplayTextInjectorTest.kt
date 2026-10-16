package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VirtualDisplayTextInjectorTest {

    @Test
    fun `printable ascii goes through input text with spaces encoded`() {
        assertThat(VirtualDisplayTextInjector.isShellTypeable("Smells Like 1991")).isTrue()
        assertThat(VirtualDisplayTextInjector.encodeForInputText("Smells Like 1991")).isEqualTo("Smells%sLike%s1991")
        assertThat(VirtualDisplayTextInjector.isShellTypeable("it\'s \"quoted\" & <ok>!")).isTrue()
    }

    @Test
    fun `non ascii, percent and control characters take the clipboard path`() {
        assertThat(VirtualDisplayTextInjector.isShellTypeable("1991\u5e74\u306e\u9999\u308a")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("100% done")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("tab\there")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("line\nbreak")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("")).isFalse()
    }
}
