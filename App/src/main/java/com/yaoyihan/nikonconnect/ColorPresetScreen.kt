@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yaoyihan.nikonconnect

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private object PresetMotion {
    const val Stiffness = 280f
    const val Damping = .88f
    const val SwipeFraction = .18f
    const val FlingVelocity = 650f
    const val ActiveDot = 22
    const val SearchDuration = 300
}

@Composable
internal fun ColorPresetScreen(vm: MainViewModel, openWatermark: () -> Unit) {
    val luts by vm.luts.collectAsState()
    val config by vm.config.collectAsState()
    val stageLayer = rememberGraphicsLayer()
    val stageBackdrop = remember(stageLayer) { BridgeBackdrop(stageLayer) }
    val updating by vm.updatingLutCovers.collectAsState()
    var importing by remember { mutableIntStateOf(0) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = LutCategory.entries.firstOrNull { it.name == categoryName }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    val menuBackdrop = LocalBridgeBackdrop.current
    var menuSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var menuSnapshotPosition by remember { mutableStateOf(Offset.Zero) }
    var menuWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(libraryMenu) {
        if (libraryMenu && menuBackdrop != null) {
            withFrameNanos { }
            if (menuBackdrop.layer.size.width > 0) {
                menuSnapshotPosition = menuBackdrop.position
                menuSnapshot = runCatching { menuBackdrop.layer.toImageBitmap() }.getOrNull()
            }
        }
    }
    val searchFocus = remember { FocusRequester() }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var sheetFull by rememberSaveable { mutableStateOf(false) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var coverTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val assets = LocalContext.current.assets
    val titleFont = remember(assets) {
        FontFamily(Font("watermark_fonts/AlibabaPuHuiTi-2-85-Bold.otf", assets, weight = FontWeight.Bold))
    }
    val listState = rememberLazyListState()
    // Freeze the ranking while browsing; deletions disappear and newly imported items fill vacancies.
    var rankedIds by remember { mutableStateOf(frequentLuts(luts).map { it.id }) }
    LaunchedEffect(luts.map { it.id }) {
        rankedIds = (rankedIds.filter { id -> luts.any { it.id == id } } + frequentLuts(luts).map { it.id }).distinct().take(4)
    }
    val featured = rankedIds.mapNotNull { id -> luts.firstOrNull { it.id == id } }
    val focused = featured.firstOrNull { it.id == focusedId } ?: featured.firstOrNull()
    val searchMatches = luts.filter { query.isBlank() || it.name.contains(query, true) || it.sourceName.contains(query, true) }
    val visible = searchMatches.filter {
        (category == null || it.category == category) &&
            when (filter) { "收藏" -> it.favorite; "最近" -> it.lastUsedAt > 0; else -> true }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importing += uris.size
        uris.forEach { vm.importLut(it) { importing = (importing - 1).coerceAtLeast(0) } }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = luts.firstOrNull { it.id == coverTargetId }
        coverTargetId = null
        if (uri != null && target != null) vm.setLutCover(target, uri)
    }
    val chooseCover: (LutEntry) -> Unit = { entry ->
        if (entry.id !in updating) {
            coverTargetId = entry.id
            runCatching { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure {
                coverTargetId = null; vm.notify("无法打开相册选择器")
            }
        }
    }
    BackHandler(!expanded && previewId == null && menuId == null) { expanded = true }
    BackHandler(expanded && sheetFull && previewId == null && menuId == null) { sheetFull = false }

    val searchTransition = updateTransition(searchVisible, label = "preset search")
    val searchProgress by searchTransition.animateFloat(
        transitionSpec = { tween(PresetMotion.SearchDuration, easing = FastOutSlowInEasing) }, label = "search reveal") {
        if (it) 1f else 0f
    }
    BackHandler(searchVisible) { searchVisible = false; focus.clearFocus() }
    FloatingMediaPage(header = {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton({ libraryMenu = true }, Modifier.bridgeGlass(CircleShape), enabled = importing == 0) {
                    if (importing > 0) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BridgeEmber)
                    else Icon(Icons.Default.Add, "导入与管理", tint = BridgeWhite)
                }
                DropdownMenu(libraryMenu, { libraryMenu = false },
                    shape = RoundedCornerShape(20.dp), containerColor = Color.Transparent,
                    tonalElevation = 0.dp, shadowElevation = 0.dp) {
                    val popupView = LocalView.current
                    val menuShape = RoundedCornerShape(20.dp)
                    Column(Modifier.testTag("presetGlassMenu").clip(menuShape).background(BridgeBackground)
                        .onGloballyPositioned {
                            val location = IntArray(2)
                            popupView.getLocationOnScreen(location)
                            menuWindowOrigin = Offset(location[0].toFloat(), location[1].toFloat())
                        }
                        .bridgeFrostedPanel(null, BridgeFrost, opacity = .5f, snapshot = menuSnapshot,
                            snapshotPosition = menuSnapshotPosition - menuWindowOrigin)
                        .bridgeColorBloom(config.colorTheme).border(BridgeGlassEdge, menuShape)) {
                        DropdownMenuItem({ Text("导入预设") }, {
                            libraryMenu = false
                            runCatching { importer.launch(arrayOf("*/*")) }.onFailure { vm.notify("无法打开文件选择器") }
                        })
                        DropdownMenuItem({ Text("水印预设") }, { libraryMenu = false; openWatermark() })
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 8.dp).height(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsLayer {
                    alpha = (1f - searchProgress * 2f).coerceIn(0f, 1f)
                    scaleX = 1f - .12f * searchProgress; scaleY = scaleX
                }.then(if (searchVisible) Modifier.clearAndSetSemantics { } else Modifier)) {
                    focused?.let { entry -> PresetImage(vm, entry, Modifier.size(40.dp)
                        .semantics { contentDescription = "预览当前预设" }
                        .clickable(enabled = !searchVisible && searchProgress < .1f, role = Role.Button) { previewId = entry.id }, imageShape = CircleShape) }
                        ?: Icon(Icons.Default.ColorLens, null, Modifier.size(32.dp), tint = BridgeWhite.copy(alpha = .65f))
                }
                if (searchTransition.currentState || searchTransition.targetState) {
                    val fieldWidth = maxWidth
                    Box(Modifier.align(Alignment.CenterEnd).width(fieldWidth * searchProgress).height(44.dp)
                        .clip(CircleShape).graphicsLayer { alpha = searchProgress }, contentAlignment = Alignment.CenterEnd) {
                        BasicTextField(query, { query = it; expanded = true },
                            Modifier.requiredWidth(fieldWidth).height(44.dp).bridgeGlass(CircleShape).focusRequester(searchFocus),
                            enabled = searchVisible, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = BridgeWhite),
                            cursorBrush = SolidColor(BridgeEmber),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                            decorationBox = { input ->
                                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = BridgeWhite.copy(alpha = .6f))
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (query.isEmpty()) Text("搜索预设", color = BridgeWhite.copy(alpha = .5f), fontSize = 13.sp)
                                        input()
                                    }
                                }
                            })
                    }
                    LaunchedEffect(searchVisible) {
                        if (searchVisible) { withFrameNanos { }; searchFocus.requestFocus() }
                    }
                }
            }
            IconButton({ searchVisible = !searchVisible; if (searchVisible) expanded = true else focus.clearFocus() },
                Modifier.bridgeGlass(CircleShape).semantics { contentDescription = if (searchVisible) "收起搜索" else "搜索预设" }) {
                Box(Modifier.size(24.dp)) {
                    Icon(Icons.Default.Search, null, Modifier.fillMaxSize().graphicsLayer {
                        alpha = 1f - searchProgress; rotationZ = searchProgress * 90f
                        scaleX = 1f - .2f * searchProgress; scaleY = scaleX
                    }, tint = BridgeWhite)
                    Icon(Icons.Default.Close, null, Modifier.fillMaxSize().graphicsLayer {
                        alpha = searchProgress; rotationZ = -90f * (1f - searchProgress)
                        scaleX = .8f + .2f * searchProgress; scaleY = scaleX
                    }, tint = BridgeWhite)
                }
            }
        }
    }) { headerInset ->
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val density = LocalDensity.current
            val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
            val compact = maxHeight < 520.dp || keyboardOpen
            val top = (headerInset - 12.dp).coerceAtLeast(0.dp)
            val titleSize = minOf(66f, maxWidth.value * .17f)
            val originalHeroHeight = ((maxHeight - top) * .47f).coerceAtMost(360.dp)
            val heroHeight by animateDpAsState(if (compact) 0.dp else (originalHeroHeight - titleSize.dp).coerceAtLeast(150.dp),
                tween(PresetMotion.SearchDuration, easing = FastOutSlowInEasing), label = "search stage resize")
            val fanHeight by animateDpAsState(if (compact) 0.dp else originalHeroHeight * .62f,
                tween(PresetMotion.SearchDuration), label = "search fan resize")
            val heroAlpha by animateFloatAsState(if (compact) 0f else 1f,
                tween(PresetMotion.SearchDuration), label = "search stage fade")
            val normalTop = top + heroHeight
            val fullTop = minOf(headerInset + 8.dp, normalTop)
            val hiddenTop = maxHeight + 24.dp
            val fullY = with(density) { fullTop.toPx() }
            val normalY = with(density) { normalTop.toPx() }
            val hiddenY = with(density) { hiddenTop.toPx() }
            val sheetOffset = remember { Animatable(if (!expanded) hiddenY else if (sheetFull) fullY else normalY) }
            var draggedY by remember { mutableStateOf<Float?>(null) }
            var settleRequest by remember { mutableIntStateOf(0) }
            var gestureId by remember { mutableIntStateOf(0) }
            val targetY = if (!expanded) hiddenY else if (sheetFull) fullY else normalY
            LaunchedEffect(targetY, settleRequest) {
                if (draggedY == null) sheetOffset.animateTo(targetY,
                    spring(dampingRatio = PresetMotion.Damping, stiffness = PresetMotion.Stiffness))
            }
            val sheetY = draggedY ?: sheetOffset.value
            val progress = ((sheetY - normalY) / (hiddenY - normalY).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val fullProgress = ((normalY - sheetY) / (normalY - fullY).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val sheetHeight = (maxHeight - fullTop).coerceAtLeast(80.dp)
            val coveredBottom = with(density) { (sheetY - fullY).coerceAtLeast(0f).toDp() }
            val dragState = rememberDraggableState { delta ->
                val proposed = (draggedY ?: sheetOffset.value) + delta
                draggedY = when {
                    proposed < fullY -> fullY + (proposed - fullY) * .18f
                    proposed > hiddenY -> hiddenY + (proposed - hiddenY) * .18f
                    else -> proposed
                }
            }
            // 联动面板：上沿直接跟手，松手后按距离与速度吸附；照片共用同一份面板位置。
            val handleDrag = Modifier.draggable(dragState, Orientation.Vertical, enabled = !compact,
                startDragImmediately = sheetOffset.isRunning,
                onDragStarted = { gestureId++; sheetOffset.stop(); draggedY = sheetOffset.value },
                onDragStopped = stopped@ { velocity ->
                    val id = gestureId
                    val released = draggedY ?: sheetOffset.value
                    sheetOffset.snapTo(released)
                    if (id != gestureId) return@stopped
                    draggedY = null
                    val anchors = listOf(fullY, normalY, hiddenY).distinct()
                    val threshold = with(density) { PresetMotion.FlingVelocity.dp.toPx() }
                    val destination = when {
                        velocity < -threshold -> anchors.lastOrNull { it < released - 1f } ?: anchors.first()
                        velocity > threshold -> anchors.firstOrNull { it > released + 1f } ?: anchors.last()
                        else -> anchors.minBy { abs(it - released) }
                    }
                    expanded = destination != hiddenY
                    sheetFull = destination == fullY
                    settleRequest++
                })
            val stageShift = (maxHeight - top - heroHeight - 120.dp).coerceAtLeast(0.dp) * .24f * progress
            Box(Modifier.matchParentSize().bridgeBackdropSource(stageBackdrop).bridgeBackground().bridgeColorBloom(config.colorTheme)) {
                if (heroHeight > 1.dp) Box(Modifier.offset(y = top + stageShift)
                    .fillMaxWidth().height(heroHeight)
                    // Only mask the stage while it collapses for the keyboard; the enlarged fan can extend below it.
                    .then(if (compact || heroAlpha < .999f) Modifier.clipToBounds() else Modifier)
                    .graphicsLayer {
                        alpha = heroAlpha * (1f - fullProgress)
                        translationY = -with(density) { 12.dp.toPx() } * fullProgress
                    }) {
                    Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(focused?.name ?: "你的色彩收藏", color = BridgeWhite.copy(alpha = .65f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 44.dp))
                        Row(Modifier.offset(y = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                            if (featured.isNotEmpty()) IconButton({ val index = featured.indexOf(focused); focusedId = featured[(index - 1).coerceAtLeast(0)].id },
                                enabled = featured.indexOf(focused) > 0, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ChevronLeft, "上一个常用预设", tint = BridgeWhite.copy(alpha = if (featured.indexOf(focused) > 0) .8f else .2f), modifier = Modifier.size(18.dp))
                            }
                            featured.forEach { entry ->
                                val active = entry.id == focused?.id
                                val width by animateDpAsState(if (active) PresetMotion.ActiveDot.dp else 6.dp, tween(180), label = "preset pagination")
                                Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 36.dp).semantics { selected = active }
                                    .clip(CircleShape).clickable(role = Role.Button, onClickLabel = "查看${entry.name}") { focusedId = entry.id }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(width, 5.dp).background(if (active) BridgeWhite else BridgeWhite.copy(alpha = .25f), CircleShape))
                                }
                            }
                            if (featured.isNotEmpty()) IconButton({ val index = featured.indexOf(focused); focusedId = featured[(index + 1).coerceAtMost(featured.lastIndex)].id },
                                enabled = featured.indexOf(focused) < featured.lastIndex, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ChevronRight, "下一个常用预设", tint = BridgeWhite.copy(alpha = if (featured.indexOf(focused) < featured.lastIndex) .8f else .2f), modifier = Modifier.size(18.dp))
                            }
                        }
                        Text("色彩", modifier = Modifier.offset(y = (-28).dp), color = BridgeWhite, fontSize = titleSize.sp, lineHeight = titleSize.sp,
                            fontFamily = titleFont, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, textAlign = TextAlign.Center)
                    }
                    if (featured.isEmpty()) {
                        Text("点击左上角 ＋ 导入第一款预设", Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                            color = BridgeWhite.copy(alpha = .6f), fontSize = 13.sp)
                    } else {
                        PresetFan(vm, featured, focused?.id, { focusedId = it }, Modifier.align(Alignment.BottomCenter)
                            .offset(y = 8.dp).height(fanHeight).fillMaxWidth()
                            .graphicsLayer { scaleX = 1f + .18f * progress - .08f * fullProgress; scaleY = scaleX })
                    }
                }
            }
            if (expanded || progress < .999f) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .offset { IntOffset(0, sheetY.toInt()) }
                .height(sheetHeight).graphicsLayer { alpha = (1f - progress).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(28.dp))
                .bridgeFrostedPanel(stageBackdrop, BridgeFrost, opacity = .5f)
                .border(BridgeGlassEdge, RoundedCornerShape(28.dp))) {
                Column(Modifier.fillMaxWidth().then(handleDrag).semantics {
                    stateDescription = if (sheetFull) "全部预设已完整展开" else "全部预设，可向上拖动展开"
                    customActions = listOf(
                        CustomAccessibilityAction("完整展开") { expanded = true; sheetFull = true; true },
                        CustomAccessibilityAction("恢复浏览") { expanded = true; sheetFull = false; true })
                }) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    Text(if (importing > 0) "导入中 $importing" else "${luts.size} 个预设",
                        Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                        color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.width(80.dp).fillMaxHeight().clickable(role = Role.Button,
                        onClickLabel = if (sheetFull) "恢复浏览" else "完整展开全部预设") { sheetFull = !sheetFull }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp, 4.dp).background(BridgeWhite.copy(alpha = .6f), CircleShape))
                    }
                    IconButton({ focus.clearFocus(); expanded = false; sheetFull = false },
                        Modifier.align(Alignment.CenterEnd), enabled = expanded) {
                        Icon(Icons.Default.Close, "收起全部预设", Modifier.size(20.dp), tint = BridgeWhite.copy(alpha = .8f))
                    }
                }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("全部", "收藏", "最近")) { title ->
                        val count = when (title) { "收藏" -> searchMatches.count { it.favorite }; "最近" -> searchMatches.count { it.lastUsedAt > 0 }; else -> searchMatches.size }
                        PresetFilter(title, count, filter == title && category == null, expanded) {
                            filter = title; categoryName = null
                        }
                    }
                    items(LutCategory.entries) { item ->
                        PresetFilter(item.title, searchMatches.count { it.category == item }, category == item, expanded) {
                            categoryName = if (category == item) null else item.name; filter = "全部"
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = (if (keyboardOpen) 24.dp else 130.dp) + coveredBottom), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (visible.isEmpty()) item {
                        Text(if (luts.isEmpty()) "导入 CUBE、PNG 或 XMP 开始调色" else "没有匹配的预设", Modifier.fillMaxWidth().padding(24.dp), color = BridgeWhite.copy(alpha = .6f), textAlign = TextAlign.Center)
                    }
                    items(visible, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(BridgeWhite.copy(alpha = .06f))
                            .clickable(enabled = expanded, role = Role.Button) { previewId = entry.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PresetImage(vm, entry, Modifier.size(48.dp, 60.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(entry.name, color = BridgeWhite, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("${entry.category.title} · ${entry.format}", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall)
                            }
                            if (entry.id in updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BridgeEmber)
                            IconButton({ vm.toggleLutFavorite(entry) }, enabled = expanded) {
                                Icon(if (entry.favorite) Icons.Default.Star else Icons.Default.StarBorder, if (entry.favorite) "取消收藏" else "收藏预设", tint = if (entry.favorite) BridgeEmber else BridgeWhite.copy(alpha = .6f))
                            }
                            IconButton({ menuId = entry.id }, enabled = expanded) { Icon(Icons.Default.MoreVert, "管理${entry.name}", tint = BridgeWhite) }
                        }
                    }
                }
            }
            if (!expanded) TextButton({ expanded = true; sheetFull = false }, Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp).bridgeGlass(CircleShape).then(handleDrag)) {
                Icon(Icons.Default.ExpandLess, null, tint = BridgeWhite)
                Text("全部预设", color = BridgeWhite)
            }
        }
    }

    luts.firstOrNull { it.id == menuId }?.let { entry ->
        BridgeGlassBottomSheet(onDismissRequest = { menuId = null }) {
            Text(entry.name, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = BridgeWhite, style = MaterialTheme.typography.titleLarge)
            DropdownMenuItem({ Text(if (entry.id in updating) "正在生成预览图…" else "设置预览图") }, { menuId = null; chooseCover(entry) }, enabled = entry.id !in updating)
            if (entry.coverKey.isNotEmpty()) DropdownMenuItem({ Text("恢复默认预览图") }, { menuId = null; vm.resetLutCover(entry) }, enabled = entry.id !in updating)
            DropdownMenuItem({ Text("重命名") }, { editName = entry.name; editId = entry.id; menuId = null })
            DropdownMenuItem({ Text("修改分类") }, { categoryId = entry.id; menuId = null })
            DropdownMenuItem({ Text("删除", color = Color(0xFFFF8080)) }, { deleteId = entry.id; menuId = null })
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    luts.firstOrNull { it.id == previewId }?.let { entry ->
        BridgeGlassBottomSheet(onDismissRequest = { previewId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            var original by remember(entry.id) { mutableStateOf(true) }
            var peeking by remember(entry.id) { mutableStateOf(false) }
            val previewHeight = minOf(420.dp, (LocalConfiguration.current.screenHeightDp * .48f).dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.name, color = BridgeWhite, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 16.dp).height(previewHeight).aspectRatio(.8f), contentAlignment = Alignment.Center) {
                    PresetImage(vm, entry, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        .pointerInput(entry.id, entry.coverKey) { detectTapGestures(onPress = { peeking = true; try { tryAwaitRelease() } finally { peeking = false } }) }, original || peeking,
                        imageShape = RoundedCornerShape(12.dp), maxEdge = 768)
                    Text(if (original || peeking) "原图" else "预设效果 · 50%", Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = .5f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ original = !original }) { Text(if (original) "查看效果" else "查看原图", color = BridgeWhite) }
                    TextButton({ chooseCover(entry) }, enabled = entry.id !in updating) {
                        Text(if (entry.id in updating) "生成中…" else "设置预览图", color = BridgeEmber)
                    }
                }
                Text("${entry.category.title} · ${entry.format} · 已使用 ${entry.useCount} 次", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(16.dp).navigationBarsPadding())
            }
        }
    }
    luts.firstOrNull { it.id == editId }?.let { entry ->
        BridgeBottomAlertDialog(onDismissRequest = { editId = null }, title = { Text("重命名预设") }, text = {
            OutlinedTextField(editName, { editName = it }, singleLine = true, colors = bridgeFieldColors())
        }, confirmButton = { TextButton({ vm.updateLut(entry, name = editName); editId = null }) { Text("保存") } }, dismissButton = { TextButton({ editId = null }) { Text("取消") } })
    }
    luts.firstOrNull { it.id == categoryId }?.let { entry ->
        BridgeBottomAlertDialog(onDismissRequest = { categoryId = null }, title = { Text("修改分类") }, text = {
            Column { LutCategory.entries.forEach { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.updateLut(entry, category = item); categoryId = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(entry.category == item, null); Text(item.title)
                }
            } }
        }, confirmButton = {})
    }
    luts.firstOrNull { it.id == deleteId }?.let { entry ->
        BridgeBottomAlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除预设") }, text = { Text("删除后不会影响已经导出的图片。") },
            confirmButton = { TextButton({ vm.removeLut(entry); deleteId = null }) { Text("删除", color = Color(0xFFFF8080)) } }, dismissButton = { TextButton({ deleteId = null }) { Text("取消") } })
    }
}

@Composable
private fun PresetFilter(title: String, count: Int, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(if (selected) BridgeWhite else Color.Transparent)
        .semantics { this.selected = selected }.clickable(enabled = enabled, role = Role.Tab, onClick = click)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (selected) BridgeNight else BridgeWhite
        Text(title, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Text(count.toString(), color = color.copy(alpha = .5f), fontSize = 11.sp)
    }
}

@Composable
private fun PresetImage(vm: MainViewModel, entry: LutEntry, modifier: Modifier, original: Boolean = true, imageShape: Shape = RoundedCornerShape(8.dp), outlined: Boolean = true, maxEdge: Int = 160) {
    val includeEffect = !original
    var cover by remember(entry.id, entry.coverKey, maxEdge, includeEffect) {
        mutableStateOf(vm.cachedLutCover(entry, maxEdge, includeEffect) ?: vm.cachedLutCover(entry, maxEdge, false))
    }
    LaunchedEffect(entry.id, entry.coverKey, maxEdge, includeEffect) {
        if (cover == null || (includeEffect && cover?.graded == null)) cover = vm.loadLutCover(entry, maxEdge, includeEffect)
    }
    Box(modifier.clip(imageShape).then(if (outlined) Modifier.border(1.25.dp, Color.White.copy(alpha = .9f), imageShape) else Modifier)
        .background(BridgeWhite.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        val bitmap = if (original) cover?.original else cover?.graded ?: cover?.original
        if (bitmap != null) Image(bitmap.asImageBitmap(), if (original) "${entry.name}原图" else "${entry.name}效果图", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (cover?.error != null) Icon(Icons.Default.BrokenImage, "预览暂不可用，请重新设置图片", tint = BridgeWhite.copy(alpha = .6f))
        else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BridgeWhite.copy(alpha = .6f))
        if (bitmap != null && includeEffect && cover?.graded == null) {
            if (cover?.error != null) Icon(Icons.Default.BrokenImage, "效果预览失败，请重新打开重试", tint = BridgeWhite)
            else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BridgeWhite)
        }
    }
}

@Composable
private fun PresetFan(vm: MainViewModel, entries: List<LutEntry>, focusedId: String?, select: (String) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var drag by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val latestSelect by rememberUpdatedState(select)
    val latestIndex by rememberUpdatedState(entries.indexOfFirst { it.id == focusedId }.coerceAtLeast(0))
    var widthPx by remember { mutableFloatStateOf(1f) }
    BoxWithConstraints(modifier.draggable(rememberDraggableState { drag += it }, Orientation.Horizontal,
        onDragStarted = { settleJob?.cancel() }, onDragStopped = { velocity ->
            val direction = when {
                abs(drag) > widthPx * PresetMotion.SwipeFraction -> if (drag < 0) 1 else -1
                abs(drag) > widthPx * .04f && abs(velocity) > PresetMotion.FlingVelocity -> if (velocity < 0) 1 else -1
                else -> 0
            }
            latestSelect(entries[(latestIndex + direction).coerceIn(0, entries.lastIndex)].id)
            settleJob = scope.launch { animate(drag, 0f, animationSpec = spring(dampingRatio = PresetMotion.Damping, stiffness = PresetMotion.Stiffness)) { value, _ -> drag = value } }
        }), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        SideEffect { widthPx = with(density) { maxWidth.toPx() } }
        val cardWidth = minOf(maxWidth / 2.8f, maxHeight / 1.6f, 146.dp)
        entries.forEachIndexed { index, entry -> key(entry.id) {
            val active = entry.id == focusedId
            val lift by animateFloatAsState(if (active) -9f else 6f, spring(stiffness = PresetMotion.Stiffness), label = "fan lift")
            val angle by animateFloatAsState((index - (entries.size - 1) / 2f) * 8f, spring(stiffness = PresetMotion.Stiffness), label = "fan angle")
            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.size(cardWidth, cardWidth * 1.25f).zIndex(if (active) 10f else index.toFloat())
                .graphicsLayer {
                    translationX = with(density) { cardWidth.toPx() } * (index - (entries.size - 1) / 2f) * .48f + drag * .24f
                    translationY = with(density) { lift.dp.toPx() }; rotationZ = angle + (drag / widthPx) * 5f
                    shadowElevation = with(density) { 8.dp.toPx() }; shape = RoundedCornerShape(10.dp); clip = true
                }.border(1.25.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp)).semantics { selected = active }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "选择${entry.name}") { latestSelect(entry.id) }
                .bridgePress(interaction)) {
                PresetImage(vm, entry, Modifier.fillMaxSize(), imageShape = RoundedCornerShape(10.dp), outlined = false, maxEdge = 384)
                Box(Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f)))))
                Text(entry.name, Modifier.align(Alignment.BottomStart).padding(9.dp), color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } }
    }
}
