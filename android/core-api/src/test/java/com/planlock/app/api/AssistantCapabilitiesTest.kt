package com.planlock.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantCapabilitiesTest {
    @Test fun parsesRequiredLocalIntents() {
        assertEquals(AssistantIntent.UNLOCKS, AssistantIntentParser.parse("今天还剩几次解锁"))
        assertEquals(AssistantIntent.REMAINING_TIME, AssistantIntentParser.parse("当前计划还剩多久"))
        assertEquals(AssistantIntent.STREAK, AssistantIntentParser.parse("连续达成几天"))
        assertEquals(AssistantIntent.TOGGLE_TODAY_PLAN, AssistantIntentParser.parse("暂停今天的计划"))
        assertEquals(AssistantIntent.TOGGLE_TODAY_PLAN, AssistantIntentParser.parse("恢复今日计划"))
    }

    @Test fun unknownInputDoesNotPretendToUnderstand() {
        assertNull(AssistantIntentParser.parse("帮我写一首诗"))
    }

    @Test fun registryHasStableUniqueEntries() {
        val entries = AssistantCapabilityRegistry.entries
        assertEquals(4, entries.size)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertEquals(setOf("unlocks", "remaining_time", "streak", "toggle_plan"), entries.map { it.id }.toSet())
    }
}
