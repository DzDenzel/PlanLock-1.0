package com.planlock.app.api

data class AssistantCapability(val id: String, val title: String, val description: String, val example: String)

object AssistantCapabilityRegistry {
    val entries = listOf(
        AssistantCapability("unlocks", "解锁记录", "查看今天的解锁使用情况", "今天还剩几次解锁"),
        AssistantCapability("remaining_time", "当前行动", "查询当前计划剩余时间", "当前计划还剩多久"),
        AssistantCapability("streak", "连续达成", "查看连续完成计划的天数", "连续达成几天"),
        AssistantCapability("toggle_plan", "计划控制", "暂停或恢复今天的计划", "暂停今天的计划"),
    )
}

enum class AssistantIntent { UNLOCKS, REMAINING_TIME, STREAK, TOGGLE_TODAY_PLAN }

object AssistantIntentParser {
    fun parse(input: String): AssistantIntent? {
        val text = input.trim().replace(" ", "")
        return when {
            ("解锁" in text) && ("几次" in text || "次数" in text || "剩余" in text) -> AssistantIntent.UNLOCKS
            ("剩" in text || "多久" in text) && ("计划" in text || "行动" in text || "时间" in text) -> AssistantIntent.REMAINING_TIME
            "连续" in text && ("几天" in text || "达成" in text) -> AssistantIntent.STREAK
            ("暂停" in text || "恢复" in text) && ("计划" in text || "今日" in text || "今天" in text) -> AssistantIntent.TOGGLE_TODAY_PLAN
            else -> null
        }
    }
}
