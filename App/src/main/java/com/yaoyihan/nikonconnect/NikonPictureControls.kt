package com.yaoyihan.nikonconnect

/** Nikon ActivePicCtrlItem identifiers; availability always comes from the camera descriptor. */
object NikonPictureControls {
    private val standard = mapOf(1L to "标准", 2L to "自然", 3L to "鲜艳", 4L to "单色",
        5L to "人像", 6L to "风景", 7L to "平面", 8L to "自动", 9L to "平面单色",
        10L to "深色调单色", 11L to "丰富色调人像")
    private val creative = listOf("梦幻", "清晨", "流行", "星期日", "忧郁", "戏剧", "寂静", "漂白",
        "忧伤", "纯净", "牛仔", "玩具", "棕褐", "蓝色", "红色", "粉色", "木炭", "石墨", "二值黑白", "碳黑")

    fun label(value: Long): String = standard[value] ?: when (value) {
        in 101L..120L -> creative[(value - 101).toInt()]
        in 201L..209L -> "自定义 C${value - 200}"
        else -> "机内模式 0x${value.toString(16).uppercase()}"
    }
}
