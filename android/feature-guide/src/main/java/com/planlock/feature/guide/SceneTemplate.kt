package com.planlock.feature.guide

import com.planlock.app.api.dto.AppItem

/**
 * 场景模板（V0.1 内置简版，见 REQ-005）。
 * 一键套用自动生成默认配置（限制/放行分类、免打扰、通知静默、提醒；时间除外），套用后仍可自定义。
 */
data class SceneTemplate(
    val type: String,
    val remindAdvanceMin: Int,
    val autoDnd: Boolean,
    val notifySilence: Boolean,
    val blacklist: List<String>,
    val whitelist: List<String>,
    val strictMode: Boolean = false
) {
    fun blacklistItems(): List<AppItem> = blacklist.map { AppItem(name = it, packageName = "") }
    fun whitelistItems(): List<AppItem> = whitelist.map { AppItem(name = it, packageName = "") }
}

object SceneTemplates {

    /** 学习模式：严格模式，仅放行应用与系统豁免功能可用。 */
    val STUDY = SceneTemplate(
        type = "学习",
        remindAdvanceMin = 15,
        autoDnd = true,
        notifySilence = true,
        blacklist = listOf("游戏", "短视频", "社交", "影音"),
        whitelist = listOf("词典", "笔记", "浏览器", "音乐"),
        strictMode = true
    )

    /** 运动模式：禁用娱乐应用 + 仅放行音乐/运动健康类 + 提前 15 分钟提醒。 */
    val SPORT = SceneTemplate(
        type = "运动",
        remindAdvanceMin = 15,
        autoDnd = false,
        notifySilence = true,
        blacklist = listOf("游戏", "短视频", "社交"),
        whitelist = listOf("音乐", "运动健康", "电话")
    )

    /** 睡眠模式：严格模式 + 通知静默，仅保留系统豁免功能。 */
    val SLEEP = SceneTemplate(
        type = "睡眠",
        remindAdvanceMin = 15,
        autoDnd = true,
        notifySilence = true,
        blacklist = listOf("游戏", "短视频", "社交", "影音"),
        whitelist = emptyList(),
        strictMode = true
    )

    val WORK = SceneTemplate("工作", 15, true, true, emptyList(), emptyList())
    val CUSTOM = SceneTemplate("自定义", 15, true, true, emptyList(), emptyList())

    fun byType(type: String): SceneTemplate? = when (type) {
        "学习" -> STUDY
        "运动" -> SPORT
        "睡眠" -> SLEEP
        "工作" -> WORK
        "自定义" -> CUSTOM
        else -> null
    }
}
