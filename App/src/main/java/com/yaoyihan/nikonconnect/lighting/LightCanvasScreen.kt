@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yaoyihan.nikonconnect.lighting

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val LightNight = Color(0xFF0B080B)
private val LightWine = Color(0xFF321318)
private val LightEmber = Color(0xFFFF7133)
private val LightWhite = Color(0xFFFFF7F1)
private val LightGlass = Color(0xCC171116)

private enum class LightPanel { COLOR, BRIGHTNESS, SPLIT, LAYOUT }

@Composable
fun LightCanvasScreen(onBack: () -> Unit, onPlay: (LightScene) -> Unit) {
    val context = LocalContext.current
    val storage = remember { LightSceneStorage(context.applicationContext) }
    var scene by remember { mutableStateOf(storage.current()) }
    var selectedId by remember { mutableStateOf(scene.rootNode.firstLeaf().id) }
    var history by remember { mutableStateOf<List<LightScene>>(emptyList()) }
    var panel by remember { mutableStateOf<LightPanel?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LightScene?>(null) }
    var deleteTarget by remember { mutableStateOf<LightScene?>(null) }
    var nameInput by remember { mutableStateOf(scene.name) }
    var presets by remember { mutableStateOf(storage.presets()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showLayoutConfirm by remember { mutableStateOf(false) }
    var pendingLayout by remember { mutableStateOf<LightNode?>(null) }
    val activity = LocalActivity.current
    val controller = remember(activity) { activity?.let(::ScreenLightController) }
    val selectedLeaf = scene.rootNode.findLeaf(selectedId)

    DisposableEffect(controller) {
        onDispose { controller?.restore() }
    }
    LaunchedEffect(scene.globalScreenBrightness) { controller?.setBrightness(scene.globalScreenBrightness) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2400)
            hint = null
        }
    }
    BackHandler { onBack() }

    fun commit(next: LightScene, select: String? = selectedId) {
        val updated = next.copy(updatedAt = System.currentTimeMillis())
        history = (history + scene).takeLast(30)
        scene = updated
        selectedId = select ?: updated.rootNode.firstLeaf().id
        storage.saveCurrent(updated)
    }

    fun commitRoot(root: LightNode) = commit(scene.copy(rootNode = root))

    fun split(direction: LightSplitDirection) {
        val id = selectedId
        if (scene.rootNode.leafCount() >= 8) {
            hint = "最多支持 8 个发光区域"
            return
        }
        panel = null
        commitRoot(scene.rootNode.splitLeaf(id, direction))
        hint = "已分割区域，可继续选择子区域"
    }

    fun chooseLayout(root: LightNode) {
        panel = null
        if (scene.rootNode.leafCount() > 1) {
            pendingLayout = root
            showLayoutConfirm = true
        } else commitRoot(root)
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(LightWine, LightNight, Color.Black)))) {
        LightCanvasRenderer(
            root = scene.rootNode,
            globalSoftness = scene.globalSoftness,
            selectedId = selectedId,
            interactive = true,
            showEditorOverlay = true,
            onTap = { id -> selectedId = id ?: selectedId },
            onRatioChanged = { id, ratio -> commitRoot(scene.rootNode.updateSplitRatio(id, ratio)) },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = LightGlass, border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .12f))) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = LightWhite) }
            }
            Spacer(Modifier.width(10.dp))
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = LightGlass, border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .12f))) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 9.dp)) {
                    Text("屏幕补光", color = LightWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall)
                    Text(scene.name, color = LightWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = LightGlass, border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .12f))) {
                IconButton(onClick = { if (history.isNotEmpty()) { val previous = history.last(); history = history.dropLast(1); scene = previous; selectedId = previous.rootNode.firstLeaf().id; storage.saveCurrent(previous) } }) {
                    Icon(Icons.Default.Undo, "撤销", tint = if (history.isEmpty()) LightWhite.copy(alpha = .3f) else LightWhite)
                }
            }
            Box {
                Surface(shape = RoundedCornerShape(18.dp), color = LightGlass, border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .12f))) {
                    IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, "更多", tint = LightWhite) }
                }
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Save, null) }, text = { Text("保存当前预设") }, onClick = { nameInput = scene.name; showSaveDialog = true; showMore = false })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, text = { Text("光场预设") }, onClick = { presets = storage.presets(); showPresets = true; showMore = false })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Tune, null) }, text = { Text("恢复为单一区域") }, onClick = { commitRoot(LightNode.Leaf()); showMore = false })
                }
            }
        }

        hint?.let { text ->
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 78.dp), shape = RoundedCornerShape(18.dp), color = LightGlass, border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .14f))) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = LightWhite, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            shape = RoundedCornerShape(24.dp),
            color = LightGlass,
            border = androidx.compose.foundation.BorderStroke(1.dp, LightWhite.copy(alpha = .13f)),
            shadowElevation = 14.dp,
        ) {
            Row(Modifier.padding(horizontal = 5.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                LightAction("颜色", Icons.Default.ColorLens, panel == LightPanel.COLOR) { panel = LightPanel.COLOR }
                LightAction("亮度", Icons.Default.BrightnessHigh, panel == LightPanel.BRIGHTNESS) { panel = LightPanel.BRIGHTNESS }
                LightAction("分割", Icons.Default.ViewColumn, panel == LightPanel.SPLIT) { panel = LightPanel.SPLIT }
                LightAction("布局", Icons.Default.ViewStream, panel == LightPanel.LAYOUT) { panel = LightPanel.LAYOUT }
                Button(
                    onClick = { storage.saveCurrent(scene); onPlay(scene) },
                    modifier = Modifier.weight(1.18f).height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LightEmber, contentColor = LightNight),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(19.dp)); Spacer(Modifier.width(3.dp)); Text("发光", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    when (panel) {
        LightPanel.COLOR -> ColorSheet(selectedLeaf, { color -> selectedId?.let { commitRoot(scene.rootNode.updateLeaf(it) { leaf -> leaf.copy(colorArgb = color.toArgb()) }) } }, { panel = null })
        LightPanel.BRIGHTNESS -> BrightnessSheet(
            leaf = selectedLeaf,
            global = scene.globalScreenBrightness,
            globalSoftness = scene.globalSoftness,
            setIntensity = { value -> selectedId?.let { commitRoot(scene.rootNode.updateLeaf(it) { leaf -> leaf.copy(intensity = value) }) } },
            setGlobal = { value -> commit(scene.copy(globalScreenBrightness = value)) },
            setGlobalSoftness = { value -> commit(scene.copy(globalSoftness = value)) },
            dismiss = { panel = null },
        )
        LightPanel.SPLIT -> SplitSheet(scene.rootNode, selectedId, { split(LightSplitDirection.VERTICAL) }, { split(LightSplitDirection.HORIZONTAL) }, { selectedId?.let { if (scene.rootNode.canMergeLeaf(it)) { commitRoot(scene.rootNode.mergeLeaf(it)); hint = "已合并区域" } } }, { panel = null })
        LightPanel.LAYOUT -> LayoutSheet({ chooseLayout(singleLightScene().rootNode) }, { chooseLayout(leftRightLightScene().rootNode) }, { chooseLayout(topBottomLightScene().rootNode) }, { chooseLayout(fourZoneLightScene().rootNode) }, { panel = null })
        null -> Unit
    }

    if (showSaveDialog) AlertDialog(
        onDismissRequest = { showSaveDialog = false },
        title = { Text("保存光场预设") },
        text = { OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true, label = { Text("预设名称") }) },
        confirmButton = { TextButton(onClick = { val saved = scene.copy(id = lightId(), name = nameInput.trim().ifBlank { "未命名光场" }); storage.savePreset(saved); storage.saveCurrent(saved); scene = saved; presets = storage.presets(); showSaveDialog = false }) { Text("保存", color = LightEmber) } },
        dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } },
        containerColor = LightGlass,
    )

    if (showLayoutConfirm) AlertDialog(
        onDismissRequest = { showLayoutConfirm = false; pendingLayout = null },
        title = { Text("应用新布局") },
        text = { Text("应用新布局会替换当前光场，是否继续？") },
        confirmButton = { TextButton(onClick = { pendingLayout?.let(::commitRoot); pendingLayout = null; showLayoutConfirm = false }) { Text("继续", color = LightEmber) } },
        dismissButton = { TextButton(onClick = { showLayoutConfirm = false; pendingLayout = null }) { Text("取消") } },
        containerColor = LightGlass,
    )

    if (showPresets) ModalBottomSheet(onDismissRequest = { showPresets = false }, containerColor = LightGlass) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("光场预设", color = LightWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (presets.isEmpty()) Text("还没有保存的预设", color = LightWhite.copy(alpha = .6f), modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets, key = { it.id }) { preset ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = LightWhite.copy(alpha = .07f)) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { scene = preset; selectedId = preset.rootNode.firstLeaf().id; storage.saveCurrent(preset); showPresets = false }) {
                                Text(preset.name, color = LightWhite, fontWeight = FontWeight.SemiBold)
                                Text("${preset.rootNode.leafCount()} 个区域", color = LightWhite.copy(alpha = .52f), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { renameTarget = preset; nameInput = preset.name }) { Icon(Icons.Default.Edit, "重命名", tint = LightWhite.copy(alpha = .72f)) }
                            IconButton(onClick = { deleteTarget = preset }) { Icon(Icons.Default.Delete, "删除", tint = Color(0xFFFF9A9A)) }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target -> AlertDialog(
        onDismissRequest = { renameTarget = null },
        title = { Text("重命名预设") },
        text = { OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { storage.renamePreset(target.id, nameInput); presets = storage.presets(); renameTarget = null }) { Text("保存", color = LightEmber) } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        containerColor = LightGlass,
    ) }

    deleteTarget?.let { target -> AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("删除预设") },
        text = { Text("删除后不会影响当前正在编辑的光场。") },
        confirmButton = { TextButton(onClick = { storage.deletePreset(target.id); presets = storage.presets(); deleteTarget = null }) { Text("删除", color = Color(0xFFFF9A9A)) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        containerColor = LightGlass,
    ) }
}

@Composable
private fun RowScope.LightAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, click: () -> Unit) {
    TextButton(onClick = click, modifier = Modifier.weight(1f).height(52.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (selected) LightEmber else LightWhite.copy(alpha = .78f))
            Text(label, color = if (selected) LightEmber else LightWhite.copy(alpha = .78f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ColorSheet(leaf: LightNode.Leaf?, setColor: (Color) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LightGlass) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("区域颜色", color = LightWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("选择当前区域的发光颜色", color = LightWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            val colors = listOf(
                "暖白" to Color(0xFFFFE4C2), "中性白" to Color.White, "冷白" to Color(0xFFD6E9FF),
                "橙色" to Color(0xFFFF7133), "红色" to Color(0xFFFF6377), "粉色" to Color(0xFFFF9CC0),
                "紫色" to Color(0xFFB99CFF), "蓝色" to Color(0xFF78BFFF), "青色" to Color(0xFF72E0D1), "绿色" to Color(0xFF92DC9B),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(colors) { (name, color) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = leaf != null) { setColor(color) }) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(color).border(2.dp, LightWhite.copy(alpha = .35f), CircleShape))
                        Spacer(Modifier.height(4.dp)); Text(name, color = LightWhite.copy(alpha = .78f), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(17.dp))
            Text("自定义颜色", color = LightWhite, fontWeight = FontWeight.SemiBold)
            var hsv by remember(leaf?.id) { mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(leaf?.colorArgb ?: Color.White.toArgb(), it) }) }
            val custom = Color.hsv(hsv[0], hsv[1], hsv[2])
            Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(custom))
            Spacer(Modifier.height(8.dp))
            Slider(value = hsv[0], onValueChange = { hsv = floatArrayOf(it, hsv[1], hsv[2]); setColor(Color.hsv(hsv[0], hsv[1], hsv[2])) }, valueRange = 0f..360f)
            Slider(value = hsv[1], onValueChange = { hsv = floatArrayOf(hsv[0], it, hsv[2]); setColor(Color.hsv(hsv[0], hsv[1], hsv[2])) })
            Slider(value = hsv[2], onValueChange = { hsv = floatArrayOf(hsv[0], hsv[1], it); setColor(Color.hsv(hsv[0], hsv[1], hsv[2])) })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("3200K" to Color(0xFFFFB36B), "4500K" to Color(0xFFFFE4C2), "5600K" to Color.White, "6500K" to Color(0xFFD6E9FF)).forEach { (label, color) ->
                    OutlinedButton(onClick = { setColor(color) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = LightWhite)) { Text(label, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun BrightnessSheet(
    leaf: LightNode.Leaf?,
    global: Float,
    globalSoftness: Float,
    setIntensity: (Float) -> Unit,
    setGlobal: (Float) -> Unit,
    setGlobalSoftness: (Float) -> Unit,
    dismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LightGlass) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("亮度与过渡", color = LightWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("区域亮度只影响当前区域，屏幕亮度仅作用于当前窗口", color = LightWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Text("区域亮度 ${((leaf?.intensity ?: 0f) * 100).toInt()}%", color = LightWhite)
            Slider(value = leaf?.intensity ?: 0f, onValueChange = setIntensity, enabled = leaf != null)
            Text("全局柔和度 ${(globalSoftness * 100).toInt()}%", color = LightWhite)
            Slider(value = globalSoftness, onValueChange = setGlobalSoftness)
            Text("屏幕亮度 ${(global * 100).toInt()}%", color = LightWhite)
            Slider(value = global, onValueChange = setGlobal, valueRange = .2f..1f)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SplitSheet(root: LightNode, selectedId: String?, vertical: () -> Unit, horizontal: () -> Unit, merge: () -> Unit, dismiss: () -> Unit) {
    val canMerge = selectedId?.let(root::canMergeLeaf) == true
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LightGlass) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("分割区域", color = LightWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("选中一个区域后，拖动分割线可以调整比例", color = LightWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(vertical, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = LightWhite)) { Icon(Icons.Default.ViewColumn, null); Spacer(Modifier.width(5.dp)); Text("左右分割") }
                OutlinedButton(horizontal, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = LightWhite)) { Icon(Icons.Default.ViewStream, null); Spacer(Modifier.width(5.dp)); Text("上下分割") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(merge, Modifier.fillMaxWidth(), enabled = canMerge, colors = ButtonDefaults.outlinedButtonColors(contentColor = LightWhite)) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text(if (canMerge) "合并区域" else "合并区域（需选择未继续分割的区域）") }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun LayoutSheet(single: () -> Unit, leftRight: () -> Unit, topBottom: () -> Unit, four: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LightGlass) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("快捷布局", color = LightWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            listOf("单色全屏" to single, "左右双色" to leftRight, "上下双色" to topBottom, "四区光场" to four).forEach { (label, action) ->
                OutlinedButton(action, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = LightWhite), shape = RoundedCornerShape(15.dp)) { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                Spacer(Modifier.height(7.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LightCanvasRenderer(
    root: LightNode,
    globalSoftness: Float,
    selectedId: String?,
    interactive: Boolean,
    showEditorOverlay: Boolean = interactive,
    onTap: (String?) -> Unit,
    onRatioChanged: (String, Float) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingSplitId by remember(root) { mutableStateOf<String?>(null) }
    val layoutSignature = remember(root) { root.layoutSignature() }
    val layout = remember(layoutSignature, canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            layoutLightNode(root, Rect(0f, 0f, canvasSize.width.toFloat(), canvasSize.height.toFloat()))
        } else null
    }
    val neighbors = remember(layout) { layout?.let { findLeafNeighbors(it.leafLayouts) }.orEmpty() }

    val pointerModifier = if (!interactive) Modifier else Modifier.pointerInput(root, canvasSize) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val currentLayout = layout ?: return@awaitEachGesture
            val activeLine = currentLayout.splitAt(down.position, 28f)
            var moved = false
            if (activeLine != null) draggingSplitId = activeLine.id
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        if (!moved) onTap(currentLayout.hitLeaf(down.position))
                        break
                    }
                    if (activeLine != null && (change.position - down.position).getDistance() > 4f) {
                        moved = true
                        val ratio = if (activeLine.direction == LightSplitDirection.VERTICAL) {
                            (change.position.x - activeLine.parent.left) / activeLine.parent.width
                        } else {
                            (change.position.y - activeLine.parent.top) / activeLine.parent.height
                        }
                        onRatioChanged(activeLine.id, ratio.coerceIn(.2f, .8f))
                        change.consume()
                    }
                }
            } finally {
                draggingSplitId = null
            }
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { canvasSize = it }.then(pointerModifier)) {
        Canvas(Modifier.fillMaxSize()) {
            layout?.let { drawLightColorLayer(it, neighbors, root, globalSoftness) }
        }
        if (showEditorOverlay && canvasSize.width > 0 && canvasSize.height > 0) {
            Canvas(Modifier.fillMaxSize()) {
                drawEditorOverlay(layout ?: return@Canvas, selectedId, draggingSplitId)
            }
        }
    }
}

private fun DrawScope.drawLightColorLayer(layout: LightLayout, neighbors: List<LeafNeighbor>, root: LightNode, globalSoftness: Float) {
    val currentLeaves = layout.leafLayouts.associate { it.leafId to root.findLeaf(it.leafId) }
    layout.leafLayouts.forEach { leafLayout ->
        val leaf = currentLeaves[leafLayout.leafId] ?: return@forEach
        drawRect(
            color = lightDisplayColor(leaf.colorArgb, leaf.intensity),
            topLeft = Offset(leafLayout.bounds.left, leafLayout.bounds.top),
            size = androidx.compose.ui.geometry.Size(leafLayout.bounds.width, leafLayout.bounds.height),
        )
    }

    // 三区以上优先保证响应速度和边界清晰度，不启用边界渐变。
    val effectiveSoftness = if (layout.leafLayouts.size > 3) 0f else globalSoftness
    val byId = layout.leafLayouts.associateBy { it.leafId }
    val maxSoftness = min(size.width, size.height) * .16f * effectiveSoftness.coerceIn(0f, 1f)
    if (maxSoftness <= .5f) return
    neighbors.forEach { edge ->
        val first = byId[edge.firstId]
        val second = byId[edge.secondId]
        if (first == null || second == null) return@forEach
        val regionLimit = min(
            min(first.bounds.width, first.bounds.height),
            min(second.bounds.width, second.bounds.height),
        ) * .45f
        val softness = maxSoftness.coerceAtMost(regionLimit)
        if (softness <= .5f) return@forEach
        val firstLeaf = currentLeaves[first.leafId] ?: return@forEach
        val secondLeaf = currentLeaves[second.leafId] ?: return@forEach
        val firstColor = lightDisplayColor(firstLeaf.colorArgb, firstLeaf.intensity)
        val secondColor = lightDisplayColor(secondLeaf.colorArgb, secondLeaf.intensity)
        when (edge.side) {
            NeighborSide.RIGHT -> {
                val x = first.bounds.right
                clipRect(x - softness, edge.overlapStart, x + softness, edge.overlapEnd) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(firstColor, secondColor),
                            start = Offset(x - softness, 0f),
                            end = Offset(x + softness, 0f),
                        ),
                        topLeft = Offset(x - softness, edge.overlapStart),
                        size = androidx.compose.ui.geometry.Size(softness * 2f, edge.overlapEnd - edge.overlapStart),
                    )
                }
            }
            NeighborSide.BOTTOM -> {
                val y = first.bounds.bottom
                clipRect(edge.overlapStart, y - softness, edge.overlapEnd, y + softness) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(firstColor, secondColor),
                            start = Offset(0f, y - softness),
                            end = Offset(0f, y + softness),
                        ),
                        topLeft = Offset(edge.overlapStart, y - softness),
                        size = androidx.compose.ui.geometry.Size(edge.overlapEnd - edge.overlapStart, softness * 2f),
                    )
                }
            }
            NeighborSide.LEFT, NeighborSide.TOP -> Unit
        }
    }
}

private fun DrawScope.drawEditorOverlay(layout: LightLayout, selectedId: String?, draggingSplitId: String?) {
    layout.splits.forEach { line ->
        val active = line.id == draggingSplitId
        val color = if (active) LightEmber.copy(alpha = .8f) else Color.White.copy(alpha = .18f)
        val width = if (active) 2f else 1f
        if (line.direction == LightSplitDirection.VERTICAL) {
            drawLine(color, Offset(line.position, line.parent.top), Offset(line.position, line.parent.bottom), strokeWidth = width, cap = StrokeCap.Butt)
            if (active) drawRoundRect(LightEmber.copy(alpha = .72f), topLeft = Offset(line.position - 12f, line.parent.center.y - 2f), size = androidx.compose.ui.geometry.Size(24f, 4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
        } else {
            drawLine(color, Offset(line.parent.left, line.position), Offset(line.parent.right, line.position), strokeWidth = width, cap = StrokeCap.Butt)
            if (active) drawRoundRect(LightEmber.copy(alpha = .72f), topLeft = Offset(line.parent.center.x - 12f, line.position - 2f), size = androidx.compose.ui.geometry.Size(24f, 4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f))
        }
    }
    selectedId?.let { id ->
        layout.leaves[id]?.let { rect ->
            drawRect(LightEmber.copy(alpha = .72f), topLeft = Offset(rect.left + .5f, rect.top + .5f), size = androidx.compose.ui.geometry.Size(max(0f, rect.width - 1f), max(0f, rect.height - 1f)), style = Stroke(width = 1f))
        }
    }
}

@Composable
fun LightPlaybackScreen(scene: LightScene, onExit: () -> Unit) {
    val activity = LocalActivity.current
    val controller = remember(activity) { activity?.let(::ScreenLightController) }
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2300); showHint = false }
    BackHandler { onExit() }
    DisposableEffect(controller, scene.globalScreenBrightness) {
        controller?.enterPlayback(scene.globalScreenBrightness)
        onDispose { controller?.restore() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    val started = System.currentTimeMillis()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        if (System.currentTimeMillis() - started >= 800L) {
                            onExit()
                            break
                        }
                    }
                    if (released) down.consume()
                }
            },
    ) {
        LightCanvasRenderer(
            root = scene.rootNode,
            globalSoftness = scene.globalSoftness,
            selectedId = null,
            interactive = false,
            showEditorOverlay = false,
            onTap = {},
            onRatioChanged = { _, _ -> },
        )
        if (showHint) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = .66f)) { Text("发光模式下，长按屏幕退出", Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = Color.White) }
    }
}
