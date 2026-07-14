package com.yaoyihan.nikonconnect.lighting

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightSceneTest {
    @Test
    fun singleLeafUsesTheCompleteCanvas() {
        val leaves = layoutLightNode(LightNode.Leaf(), Rect(0f, 0f, 1f, 1f)).leaves.values.toList()
        assertEquals(1, leaves.size)
        assertRect(leaves.single(), 0f, 0f, 1f, 1f)
    }

    @Test
    fun verticalAndHorizontalSplitsStayInsideTheirParent() {
        val horizontal = LightNode.Split(direction = LightSplitDirection.VERTICAL, first = LightNode.Leaf(), second = LightNode.Leaf(), ratio = .5f)
        val horizontalLeaves = layoutLightNode(horizontal, Rect(0f, 0f, 1f, 1f)).leaves.values.toList()
        assertRect(horizontalLeaves[0], 0f, 0f, .5f, 1f)
        assertRect(horizontalLeaves[1], .5f, 0f, 1f, 1f)

        val vertical = LightNode.Split(direction = LightSplitDirection.HORIZONTAL, first = LightNode.Leaf(), second = LightNode.Leaf(), ratio = .5f)
        val verticalLeaves = layoutLightNode(vertical, Rect(0f, 0f, 1f, 1f)).leaves.values.toList()
        assertRect(verticalLeaves[0], 0f, 0f, 1f, .5f)
        assertRect(verticalLeaves[1], 0f, .5f, 1f, 1f)
    }

    @Test
    fun fourZonesAreIndependentAndDoNotOverlap() {
        val root = LightNode.Split(
            direction = LightSplitDirection.VERTICAL,
            first = LightNode.Split(
                direction = LightSplitDirection.HORIZONTAL,
                first = LightNode.Leaf(colorArgb = Color.Red.toArgb()),
                second = LightNode.Leaf(colorArgb = Color.Blue.toArgb()),
            ),
            second = LightNode.Split(
                direction = LightSplitDirection.HORIZONTAL,
                first = LightNode.Leaf(colorArgb = Color.Green.toArgb()),
                second = LightNode.Leaf(colorArgb = Color.Yellow.toArgb()),
            ),
        )
        val leaves = layoutLightNode(root, Rect(0f, 0f, 1f, 1f)).leaves.values.toList()
        assertEquals(4, leaves.size)
        assertRect(leaves[0], 0f, 0f, .5f, .5f)
        assertRect(leaves[1], 0f, .5f, .5f, 1f)
        assertRect(leaves[2], .5f, 0f, 1f, .5f)
        assertRect(leaves[3], .5f, .5f, 1f, 1f)
        assertEquals(1f, leaves.fold(0f) { total, rect -> total + rect.width * rect.height }, 0.0001f)
        for (i in leaves.indices) for (j in i + 1 until leaves.size) assertEquals(0f, overlapArea(leaves[i], leaves[j]), 0.0001f)
        assertTrue(leaves.all { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f })
    }

    @Test
    fun nonDefaultRatiosRemainClampedAndCorrect() {
        val root = LightNode.Split(direction = LightSplitDirection.VERTICAL, ratio = .2f, first = LightNode.Leaf(), second = LightNode.Leaf())
        assertRect(layoutLightNode(root, Rect(0f, 0f, 1f, 1f)).leaves.values.first(), 0f, 0f, .2f, 1f)
        val adjusted = root.updateSplitRatio(root.id, .95f) as LightNode.Split
        assertEquals(.8f, adjusted.ratio)
        assertRect(layoutLightNode(adjusted, Rect(0f, 0f, 1f, 1f)).leaves.values.first(), 0f, 0f, .8f, 1f)
    }

    @Test
    fun mergeOnlyMergesSelectedDirectLeafSiblings() {
        val scene = leftRightLightScene()
        val selected = scene.rootNode.firstLeaf().id
        assertTrue(scene.rootNode.canMergeLeaf(selected))
        assertEquals(1, scene.rootNode.mergeLeaf(selected).leafCount())
    }

    @Test
    fun fourZoneNeighborsCoverAllInternalEdgesWithoutDiagonalOverlap() {
        val root = fourZoneLightScene().rootNode
        val layout = layoutLightNode(root, Rect(0f, 0f, 1f, 1f))
        val neighbors = findLeafNeighbors(layout.leafLayouts, tolerance = .0001f)
        assertEquals(4, neighbors.size)
        assertEquals(2, neighbors.count { it.side == NeighborSide.RIGHT })
        assertEquals(2, neighbors.count { it.side == NeighborSide.BOTTOM })
    }

    @Test
    fun nestedSplitOrdersRemainTiledForTShapesAndFourZones() {
        val splitTopBottomThenSides = LightNode.Split(
            direction = LightSplitDirection.HORIZONTAL,
            first = LightNode.Split(direction = LightSplitDirection.VERTICAL, first = LightNode.Leaf(), second = LightNode.Leaf()),
            second = LightNode.Split(direction = LightSplitDirection.VERTICAL, first = LightNode.Leaf(), second = LightNode.Leaf()),
        )
        val splitSidesThenTopBottom = fourZoneLightScene().rootNode
        val tShape = LightNode.Split(
            direction = LightSplitDirection.VERTICAL,
            first = LightNode.Split(direction = LightSplitDirection.HORIZONTAL, first = LightNode.Leaf(), second = LightNode.Leaf()),
            second = LightNode.Leaf(),
        )

        listOf(splitTopBottomThenSides, splitSidesThenTopBottom, tShape).forEach { root ->
            val layout = layoutLightNode(root, Rect(0f, 0f, 1f, 1f))
            assertEquals(root.leafCount(), layout.leafLayouts.size)
            assertEquals(1f, layout.leafLayouts.fold(0f) { total, leaf -> total + leaf.bounds.width * leaf.bounds.height }, 0.0001f)
            for (i in layout.leafLayouts.indices) for (j in i + 1 until layout.leafLayouts.size) assertEquals(0f, overlapArea(layout.leafLayouts[i].bounds, layout.leafLayouts[j].bounds), 0.0001f)
        }
    }

    private fun assertRect(actual: Rect, left: Float, top: Float, right: Float, bottom: Float) {
        assertEquals(left, actual.left, 0.0001f)
        assertEquals(top, actual.top, 0.0001f)
        assertEquals(right, actual.right, 0.0001f)
        assertEquals(bottom, actual.bottom, 0.0001f)
    }

    private fun overlapArea(first: Rect, second: Rect): Float {
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
        return width * height
    }
}
