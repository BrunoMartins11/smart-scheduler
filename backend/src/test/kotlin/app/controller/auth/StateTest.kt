package app.controller.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateTest {

    @Test
    fun matchingStateIsAccepted() = assertTrue(stateMatches("abc123", "abc123"))

    @Test
    fun mismatchedStateIsRejected() = assertFalse(stateMatches("abc123", "attacker"))

    @Test
    fun missingStateIsRejected() {
        assertFalse(stateMatches(null, "abc123"))
        assertFalse(stateMatches("abc123", null))
    }
}
