package com.planlock.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFeedbackPresentationTest {
    @Test
    fun blockedAppIdentityAndReturnTimingArePartOfTheOverlayPresentation() {
        val presentation = BlockFeedbackPresentation.create("YouTube", animatorDurationScale = 1f)

        assertEquals("YouTube", presentation.appName)
        assertEquals("应用已锁定", presentation.title)
        assertEquals("当前计划正在限制此应用", presentation.message)
        assertEquals(900L, presentation.displayMillis)
        assertTrue(presentation.animate)
    }

    @Test
    fun zeroAnimatorScaleUsesImmediateFinalStateAndBlankLabelGetsFallback() {
        val presentation = BlockFeedbackPresentation.create("  ", animatorDurationScale = 0f)

        assertEquals("此应用", presentation.appName)
        assertFalse(presentation.animate)
    }
}
