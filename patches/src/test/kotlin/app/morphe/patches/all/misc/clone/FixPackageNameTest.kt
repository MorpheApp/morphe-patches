package app.morphe.patches.all.misc.clone

import kotlin.test.Test
import kotlin.test.assertEquals

class FixPackageNameTest {
    private val validNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private fun assertFixed(expected: String, input: String) {
        val fixed = fixPackageName(input)
        assertEquals(expected, fixed, "Input: \"$input\"")
        check(fixed.matches(validNamePattern)) { "Not valid: $fixed" }
    }

    @Test
    fun validNamesAreUnchanged() {
        assertFixed("com.google.android.youtube", "com.google.android.youtube")
        assertFixed("app.morphe.Music_2", "app.morphe.Music_2")
        assertFixed("A.b", "A.b")
    }

    @Test
    fun whitespaceIsReplaced() {
        assertFixed("com.my_app", "com.my app")
        assertFixed("com.my_app", "com.my   app")
        assertFixed("com.foo.bar", " com. foo .bar ")
    }

    @Test
    fun emptySegmentsAreRemoved() {
        assertFixed("com.foo", "com..foo")
        assertFixed("com.foo", ".com.foo.")
    }

    @Test
    fun otherInvalidCharactersAppendSuffix() {
        assertFixed("com.my_app.fixedname", "com.my-app")
        assertFixed("com.caf_.fixedname", "com.café")
        assertFixed("com.my_app_x.fixedname", "com.my app!x")
    }

    @Test
    fun segmentsNotStartingWithLetterArePrefixed() {
        assertFixed("com.app2fa.fixedname", "com.2fa")
        assertFixed("com.app_test.fixedname", "com._test")
    }

    @Test
    fun singleSegmentAppendsSuffix() {
        assertFixed("myclone.fixedname", "myclone")
        assertFixed("my_clone.fixedname", "my clone")
        assertFixed("app___.fixedname", "!!!")
    }
}
