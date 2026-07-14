package com.yaoyihan.nikonconnect.lighting

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.UUID

enum class LightSplitDirection { VERTICAL, HORIZONTAL }

sealed class LightNode(open val id: String) {
    data class Leaf(
        override val id: String = lightId(),
        val colorArgb: Int = Color.White.toArgb(),
        val intensity: Float = 1f,
        val transitionSoftness: Float = .28f,
    ) : LightNode(id)

    data class Split(
        override val id: String = lightId(),
        val direction: LightSplitDirection,
        val ratio: Float = .5f,
        val first: LightNode,
        val second: LightNode,
    ) : LightNode(id)
}

data class LightScene(
    val id: String = lightId(),
    val name: String = "未命名光场",
    val rootNode: LightNode = LightNode.Leaf(),
    val globalScreenBrightness: Float = .9f,
    val globalSoftness: Float = .28f,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class LightSplitLine(
    val id: String,
    val direction: LightSplitDirection,
    val parent: Rect,
    val position: Float,
)

data class LeafLayout(
    val leafId: String,
    val bounds: Rect,
    val leaf: LightNode.Leaf,
)

data class LightLayout(
    val leaves: Map<String, Rect>,
    val splits: List<LightSplitLine>,
    val leafLayouts: List<LeafLayout> = emptyList(),
)

fun lightId(): String = UUID.randomUUID().toString()

fun LightNode.leafCount(): Int = when (this) {
    is LightNode.Leaf -> 1
    is LightNode.Split -> first.leafCount() + second.leafCount()
}

fun LightNode.layoutSignature(): String = when (this) {
    is LightNode.Leaf -> "leaf:$id"
    is LightNode.Split -> "split:$id:${direction.name}:${ratio}:${first.layoutSignature()}:${second.layoutSignature()}"
}

fun LightNode.firstLeaf(): LightNode.Leaf = when (this) {
    is LightNode.Leaf -> this
    is LightNode.Split -> first.firstLeaf()
}

fun LightNode.findLeaf(id: String): LightNode.Leaf? = when (this) {
    is LightNode.Leaf -> takeIf { it.id == id }
    is LightNode.Split -> first.findLeaf(id) ?: second.findLeaf(id)
}

fun LightNode.updateLeaf(id: String, change: (LightNode.Leaf) -> LightNode.Leaf): LightNode = when (this) {
    is LightNode.Leaf -> if (this.id == id) change(this) else this
    is LightNode.Split -> copy(first = first.updateLeaf(id, change), second = second.updateLeaf(id, change))
}

fun LightNode.splitLeaf(id: String, direction: LightSplitDirection): LightNode = when (this) {
    is LightNode.Leaf -> if (this.id == id) {
        LightNode.Split(
            direction = direction,
            first = copy(id = lightId()),
            second = copy(id = lightId()),
        )
    } else this
    is LightNode.Split -> copy(first = first.splitLeaf(id, direction), second = second.splitLeaf(id, direction))
}

fun LightNode.canMergeLeaf(id: String): Boolean = when (this) {
    is LightNode.Leaf -> false
    is LightNode.Split -> {
        val directChild = (first is LightNode.Leaf && first.id == id) || (second is LightNode.Leaf && second.id == id)
        directChild && first is LightNode.Leaf && second is LightNode.Leaf || first.canMergeLeaf(id) || second.canMergeLeaf(id)
    }
}

fun LightNode.mergeLeaf(id: String): LightNode = when (this) {
    is LightNode.Leaf -> this
    is LightNode.Split -> {
        if (first is LightNode.Leaf && second is LightNode.Leaf && (first.id == id || second.id == id)) {
            first.copy(id = first.id)
        } else {
            copy(first = first.mergeLeaf(id), second = second.mergeLeaf(id))
        }
    }
}

fun LightNode.updateSplitRatio(id: String, ratio: Float): LightNode = when (this) {
    is LightNode.Leaf -> this
    is LightNode.Split -> if (this.id == id) copy(ratio = ratio.coerceIn(.2f, .8f))
    else copy(first = first.updateSplitRatio(id, ratio), second = second.updateSplitRatio(id, ratio))
}

fun layoutLightNode(root: LightNode, bounds: Rect): LightLayout {
    val leaves = linkedMapOf<String, Rect>()
    val splits = mutableListOf<LightSplitLine>()
    val leafLayouts = mutableListOf<LeafLayout>()

    fun visit(node: LightNode, rect: Rect) {
        when (node) {
            is LightNode.Leaf -> {
                leaves[node.id] = rect
                leafLayouts += LeafLayout(node.id, rect, node)
            }
            is LightNode.Split -> {
                val ratio = node.ratio.coerceIn(.2f, .8f)
                if (node.direction == LightSplitDirection.VERTICAL) {
                    val x = rect.left + rect.width * ratio
                    splits += LightSplitLine(node.id, node.direction, rect, x)
                    visit(node.first, Rect(rect.left, rect.top, x, rect.bottom))
                    visit(node.second, Rect(x, rect.top, rect.right, rect.bottom))
                } else {
                    val y = rect.top + rect.height * ratio
                    splits += LightSplitLine(node.id, node.direction, rect, y)
                    visit(node.first, Rect(rect.left, rect.top, rect.right, y))
                    visit(node.second, Rect(rect.left, y, rect.right, rect.bottom))
                }
            }
        }
    }

    visit(root, bounds)
    return LightLayout(leaves, splits, leafLayouts)
}

fun LightLayout.hitLeaf(point: Offset): String? = leaves.entries.firstOrNull { it.value.contains(point) }?.key

fun LightLayout.splitAt(point: Offset, tolerance: Float): LightSplitLine? = splits.minByOrNull { line ->
    if (line.direction == LightSplitDirection.VERTICAL) kotlin.math.abs(point.x - line.position)
    else kotlin.math.abs(point.y - line.position)
}.takeIf { line ->
    line != null && line.parent.contains(point) &&
        if (line.direction == LightSplitDirection.VERTICAL) kotlin.math.abs(point.x - line.position) <= tolerance
        else kotlin.math.abs(point.y - line.position) <= tolerance
}

fun lightDisplayColor(argb: Int, intensity: Float): Color {
    val source = Color(argb)
    val amount = intensity.coerceIn(0f, 1f)
    return Color(source.red * amount, source.green * amount, source.blue * amount, 1f)
}

fun singleLightScene(name: String = "单色全屏"): LightScene = LightScene(name = name, rootNode = LightNode.Leaf(colorArgb = Color.White.toArgb()))

fun leftRightLightScene(name: String = "左右双色"): LightScene = LightScene(
    name = name,
    rootNode = LightNode.Split(
        direction = LightSplitDirection.VERTICAL,
        first = LightNode.Leaf(colorArgb = Color(0xFFFFB26B).toArgb()),
        second = LightNode.Leaf(colorArgb = Color(0xFF78BFFF).toArgb()),
    ),
)

fun topBottomLightScene(name: String = "上下双色"): LightScene = LightScene(
    name = name,
    rootNode = LightNode.Split(
        direction = LightSplitDirection.HORIZONTAL,
        first = LightNode.Leaf(colorArgb = Color(0xFFFFB26B).toArgb()),
        second = LightNode.Leaf(colorArgb = Color(0xFF78BFFF).toArgb()),
    ),
)

fun fourZoneLightScene(name: String = "四区光场"): LightScene = LightScene(
    name = name,
    rootNode = LightNode.Split(
        direction = LightSplitDirection.VERTICAL,
        first = LightNode.Split(
            direction = LightSplitDirection.HORIZONTAL,
            first = LightNode.Leaf(colorArgb = Color(0xFFFFB26B).toArgb()),
            second = LightNode.Leaf(colorArgb = Color(0xFFFF7F9D).toArgb()),
        ),
        second = LightNode.Split(
            direction = LightSplitDirection.HORIZONTAL,
            first = LightNode.Leaf(colorArgb = Color(0xFF8BC7FF).toArgb()),
            second = LightNode.Leaf(colorArgb = Color(0xFFBBA2FF).toArgb()),
        ),
    ),
)
