package com.planlock.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneTemplatesTest {
    @Test
    fun strictModeDefaultsMatchEachScene() {
        mapOf("学习" to true, "睡眠" to true, "运动" to false, "工作" to false, "自定义" to false)
            .forEach { (type, expected) ->
                assertEquals(type, expected, SceneTemplates.byType(type)?.strictMode)
            }
    }
}
