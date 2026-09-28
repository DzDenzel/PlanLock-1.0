package com.planlock.app.engine

internal data class BlockFeedbackPresentation(
    val appName: String,
    val title: String,
    val message: String,
    val displayMillis: Long,
    val animate: Boolean,
) {
    companion object {
        fun create(appName: String?, animatorDurationScale: Float) = BlockFeedbackPresentation(
            appName = appName?.trim().takeUnless { it.isNullOrEmpty() } ?: "此应用",
            title = "应用已锁定",
            message = "当前计划正在限制此应用",
            displayMillis = 900L,
            animate = animatorDurationScale > 0f,
        )
    }
}
