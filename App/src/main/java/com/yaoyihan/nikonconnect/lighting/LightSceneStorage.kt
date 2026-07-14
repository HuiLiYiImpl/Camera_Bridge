package com.yaoyihan.nikonconnect.lighting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LightSceneStorage(context: Context) {
    private val preferences = context.getSharedPreferences("screen_light_scenes", Context.MODE_PRIVATE)

    init {
        if (!preferences.contains(PRESETS_KEY)) {
            savePresets(defaultScenes())
        }
    }

    fun current(): LightScene = preferences.getString(CURRENT_KEY, null)?.let(::decodeScene) ?: singleLightScene()

    fun saveCurrent(scene: LightScene) {
        preferences.edit().putString(CURRENT_KEY, encodeScene(scene)).apply()
    }

    fun presets(): List<LightScene> = runCatching {
        val array = JSONArray(preferences.getString(PRESETS_KEY, "[]"))
        buildList(array.length()) { for (index in 0 until array.length()) add(decodeScene(array.getJSONObject(index))) }
    }.getOrDefault(emptyList())

    fun savePreset(scene: LightScene) {
        val presets = presets().filterNot { it.id == scene.id } + scene.copy(updatedAt = System.currentTimeMillis())
        savePresets(presets)
    }

    fun renamePreset(id: String, name: String) {
        val cleanName = name.trim().ifBlank { return }
        savePresets(presets().map { if (it.id == id) it.copy(name = cleanName, updatedAt = System.currentTimeMillis()) else it })
    }

    fun deletePreset(id: String) {
        savePresets(presets().filterNot { it.id == id })
    }

    private fun savePresets(scenes: List<LightScene>) {
        val array = JSONArray()
        scenes.forEach { array.put(JSONObject(encodeScene(it))) }
        preferences.edit().putString(PRESETS_KEY, array.toString()).apply()
    }

    private fun encodeScene(scene: LightScene): String = JSONObject().apply {
        put("id", scene.id)
        put("name", scene.name)
        put("brightness", scene.globalScreenBrightness)
        put("globalSoftness", scene.globalSoftness)
        put("updatedAt", scene.updatedAt)
        put("root", encodeNode(scene.rootNode))
    }.toString()

    private fun encodeNode(node: LightNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        when (node) {
            is LightNode.Leaf -> {
                put("kind", "leaf")
                put("color", node.colorArgb)
                put("intensity", node.intensity)
                put("softness", node.transitionSoftness)
            }
            is LightNode.Split -> {
                put("kind", "split")
                put("direction", node.direction.name)
                put("ratio", node.ratio)
                put("first", encodeNode(node.first))
                put("second", encodeNode(node.second))
            }
        }
    }

    private fun decodeScene(value: String): LightScene = decodeScene(JSONObject(value))

    private fun decodeScene(value: JSONObject): LightScene = LightScene(
        id = value.optString("id", lightId()),
        name = value.optString("name", "未命名光场"),
        rootNode = decodeNode(value.optJSONObject("root") ?: JSONObject()),
        globalScreenBrightness = value.optDouble("brightness", .9).toFloat().coerceIn(.2f, 1f),
        globalSoftness = value.optDouble("globalSoftness", averageLeafSoftness(value.optJSONObject("root"))).toFloat().coerceIn(0f, 1f),
        updatedAt = value.optLong("updatedAt", System.currentTimeMillis()),
    )

    private fun averageLeafSoftness(node: JSONObject?): Double {
        if (node == null) return .28
        val values = mutableListOf<Double>()
        fun collect(value: JSONObject) {
            if (value.optString("kind") == "split") {
                value.optJSONObject("first")?.let(::collect)
                value.optJSONObject("second")?.let(::collect)
            } else values += value.optDouble("softness", .28)
        }
        collect(node)
        return values.average().takeIf { it.isFinite() } ?: .28
    }

    private fun decodeNode(value: JSONObject): LightNode {
        val id = value.optString("id", lightId())
        return if (value.optString("kind") == "split") {
            LightNode.Split(
                id = id,
                direction = runCatching { LightSplitDirection.valueOf(value.optString("direction")) }.getOrDefault(LightSplitDirection.VERTICAL),
                ratio = value.optDouble("ratio", .5).toFloat().coerceIn(.2f, .8f),
                first = decodeNode(value.optJSONObject("first") ?: JSONObject()),
                second = decodeNode(value.optJSONObject("second") ?: JSONObject()),
            )
        } else {
            LightNode.Leaf(
                id = id,
                colorArgb = value.optInt("color", -1),
                intensity = value.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1f),
                transitionSoftness = value.optDouble("softness", .28).toFloat().coerceIn(0f, 1f),
            )
        }
    }

    private fun defaultScenes(): List<LightScene> = listOf(
        singleLightScene("暖白补光").copy(rootNode = LightNode.Leaf(colorArgb = 0xFFFFE4C2.toInt())),
        leftRightLightScene("冷暖双色"),
        LightScene(name = "橙蓝电影光", rootNode = LightNode.Split(
            direction = LightSplitDirection.VERTICAL,
            first = LightNode.Leaf(colorArgb = 0xFFFF6A36.toInt()),
            second = LightNode.Leaf(colorArgb = 0xFF3A7DFF.toInt()),
        )),
        LightScene(name = "粉紫氛围光", rootNode = LightNode.Split(
            direction = LightSplitDirection.VERTICAL,
            first = LightNode.Leaf(colorArgb = 0xFFFF8FAF.toInt()),
            second = LightNode.Leaf(colorArgb = 0xFFB89CFF.toInt()),
        )),
        fourZoneLightScene(),
    )

    private companion object {
        const val CURRENT_KEY = "current"
        const val PRESETS_KEY = "presets"
    }
}
