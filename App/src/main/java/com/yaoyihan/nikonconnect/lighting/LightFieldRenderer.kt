package com.yaoyihan.nikonconnect.lighting

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class NeighborSide { LEFT, RIGHT, TOP, BOTTOM }

data class LeafNeighbor(
    val firstId: String,
    val secondId: String,
    val side: NeighborSide,
    val overlapStart: Float,
    val overlapEnd: Float,
)

fun findLeafNeighbors(leaves: List<LeafLayout>, tolerance: Float = .5f): List<LeafNeighbor> {
    val result = mutableListOf<LeafNeighbor>()
    for (firstIndex in leaves.indices) {
        for (secondIndex in firstIndex + 1 until leaves.size) {
            val first = leaves[firstIndex]
            val second = leaves[secondIndex]
            val verticalOverlap = overlap(first.bounds.top, first.bounds.bottom, second.bounds.top, second.bounds.bottom)
            val horizontalOverlap = overlap(first.bounds.left, first.bounds.right, second.bounds.left, second.bounds.right)
            when {
                abs(first.bounds.right - second.bounds.left) <= tolerance && verticalOverlap > tolerance -> result += LeafNeighbor(first.leafId, second.leafId, NeighborSide.RIGHT, max(first.bounds.top, second.bounds.top), min(first.bounds.bottom, second.bounds.bottom))
                abs(second.bounds.right - first.bounds.left) <= tolerance && verticalOverlap > tolerance -> result += LeafNeighbor(second.leafId, first.leafId, NeighborSide.RIGHT, max(first.bounds.top, second.bounds.top), min(first.bounds.bottom, second.bounds.bottom))
                abs(first.bounds.bottom - second.bounds.top) <= tolerance && horizontalOverlap > tolerance -> result += LeafNeighbor(first.leafId, second.leafId, NeighborSide.BOTTOM, max(first.bounds.left, second.bounds.left), min(first.bounds.right, second.bounds.right))
                abs(second.bounds.bottom - first.bounds.top) <= tolerance && horizontalOverlap > tolerance -> result += LeafNeighbor(second.leafId, first.leafId, NeighborSide.BOTTOM, max(first.bounds.left, second.bounds.left), min(first.bounds.right, second.bounds.right))
            }
        }
    }
    return result
}

private fun overlap(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float = (min(firstEnd, secondEnd) - max(firstStart, secondStart)).coerceAtLeast(0f)
