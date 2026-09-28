package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionKindTest {
    @Test
    fun `location is the sixth optional runtime capability`() {
        assertEquals(6, PermissionKind.entries.size)
        assertTrue(PermissionKind.LOCATION.runtime)
        assertFalse(PermissionKind.LOCATION.requiredForExecution)
        assertTrue(PermissionKind.entries.filterNot { it == PermissionKind.LOCATION }.all { !it.runtime })
    }
}
