package com.yaoyihan.nikonconnect

import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

/** Once per app process, including activity recreation and returning from other tabs. */
internal object ConnectionLaunchSession {
    private var claimed = false
    fun claim(): Boolean = (!claimed).also { claimed = true }
}

internal class ConnectionIntroState(private val scope: CoroutineScope, play: Boolean) {
    private val fall = Animatable(if (play) 0f else 1000f)
    private val gather = Animatable(if (play) 0f else 1f)
    private val reveal = Animatable(if (play) 0f else 1f)
    private var job: Job? = null
    private var started = false
    var complete by mutableStateOf(!play)
        private set
    val fallTime get() = if (complete) 1000f else fall.value
    val gatherProgress get() = if (complete) 1f else gather.value
    val revealProgress get() = if (complete) 1f else reveal.value

    fun start() {
        if (started || complete) return
        started = true
        job = scope.launch {
            coroutineScope {
                val animationScope = this
                var gathering = false
                var revealing = false
                fall.animateTo(1000f, tween(1700, easing = LinearEasing)) {
                    if (value >= 540f && !gathering) {
                        gathering = true
                        animationScope.launch { gather.animateTo(1f, spring(dampingRatio = .88f, stiffness = 160f)) }
                    }
                    if (value >= 720f && !revealing) {
                        revealing = true
                        animationScope.launch { reveal.animateTo(1f, tween(440, easing = FastOutSlowInEasing)) }
                    }
                }
            }
            complete = true
        }
    }

    fun skip() {
        if (complete) return
        started = true
        job?.cancel()
        job = scope.launch {
            coroutineScope {
                launch { fall.animateTo(1000f, tween(120)) }
                launch { gather.animateTo(1f, tween(120)) }
                launch { reveal.animateTo(1f, tween(120)) }
            }
            complete = true
        }
    }

    fun finishImmediately() { job?.cancel(); complete = true }
}

private data class ConnectionPhoto(val bitmap: Bitmap, val originalUri: String? = null, val example: String? = null)

private object ConnectionLaunchPhotos {
    var cached: List<ConnectionPhoto> = emptyList()
    var hero: Bitmap? = null
    val heroIndex = kotlin.random.Random.nextInt(4)
    val examples = listOf("forest-homestead", "turquoise-lake", "alpine-glow", "green-door")
}

private fun connectionExample(context: android.content.Context, name: String, sample: Int): Bitmap? = runCatching {
    val original = context.assets.open("ai_skills/photo-to-zine-postcard/assets/$name.png").use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null
    // Keep the same crop at both resolutions, excluding the bundled poster's caption.
    Bitmap.createBitmap(original, (original.width * .06f).toInt(), (original.height * .05f).toInt(),
        (original.width * .88f).toInt(), (original.height * .44f).toInt()).also { if (it !== original) original.recycle() }
}.getOrNull()

@Composable
private fun connectionPhotos(vm: MainViewModel): List<ConnectionPhoto> {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(ConnectionLaunchPhotos.cached) }
    LaunchedEffect(vm) {
        if (photos.isNotEmpty()) return@LaunchedEffect
        photos = withContext(Dispatchers.IO) {
            val recent = vm.downloads.value.filter { mediaKindFor(null, it.name) == MediaKind.IMAGE }
                .sortedByDescending { it.completedAt }.take(4)
            val local = recent.mapNotNull { record ->
                val bitmap = vm.cachedLocalThumbnail(record)?.let { cached ->
                    val scale = minOf(1f, 320f / maxOf(cached.width, cached.height))
                    Bitmap.createScaledBitmap(cached, (cached.width * scale).toInt().coerceAtLeast(1), (cached.height * scale).toInt().coerceAtLeast(1), true)
                } ?: loadDownloadThumbnail(context, record.uri, MediaKind.IMAGE, maxDimension = 320)
                bitmap?.let { ConnectionPhoto(it, originalUri = record.uri) }
            }
            local.ifEmpty {
                ConnectionLaunchPhotos.examples.mapNotNull { name ->
                    connectionExample(context, name, sample = 4)?.let { ConnectionPhoto(it, example = name) }
                }
            }
        }.also { ConnectionLaunchPhotos.cached = it }
    }
    return photos
}

@Composable
private fun connectionHeroPhoto(photos: List<ConnectionPhoto>): Bitmap? {
    if (photos.isEmpty()) return null
    val context = LocalContext.current
    val photo = photos[ConnectionLaunchPhotos.heroIndex % photos.size]
    var hero by remember(photo) { mutableStateOf(ConnectionLaunchPhotos.hero ?: photo.bitmap) }
    LaunchedEffect(photo) {
        if (ConnectionLaunchPhotos.hero != null) return@LaunchedEffect
        val original = withContext(Dispatchers.IO) {
            photo.originalUri?.let { loadDownloadThumbnail(context, it, MediaKind.IMAGE, maxDimension = 2048) }
                ?: photo.example?.let { connectionExample(context, it, sample = 1) }
        }
        if (original != null) {
            ConnectionLaunchPhotos.hero = original
            hero = original
        }
    }
    return hero
}

@Composable
private fun reducedLaunchMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** The opening's final frame is the connection page; there is no splash-window handoff. */
@Composable
internal fun ConnectionLanding(vm: MainViewModel, intro: ConnectionIntroState, connected: Boolean,
    openSettings: () -> Unit, content: @Composable () -> Unit) {
    val photos = connectionPhotos(vm)
    val hero = connectionHeroPhoto(photos)
    val config by vm.config.collectAsState()
    val busy by vm.isBusy.collectAsState()
    val reduced = reducedLaunchMotion()
    val backgroundLayer = rememberGraphicsLayer()
    val backdrop = remember(backgroundLayer) { BridgeBackdrop(backgroundLayer) }
    LaunchedEffect(photos.isNotEmpty(), reduced) {
        if (reduced) intro.finishImmediately() else if (photos.isNotEmpty()) intro.start()
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().clipToBounds().testTag("connectionLanding")
        .pointerInput(intro) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!intro.complete) intro.skip()
            }
        }) {
        val heroHeight = maxWidth - BridgeDockSideGap * 2
        Box(Modifier.matchParentSize().bridgeBackdropSource(backdrop).bridgeBackground().bridgeColorBloom(config.colorTheme))
        Surface(Modifier.padding(horizontal = BridgeDockSideGap).fillMaxWidth().height(heroHeight)
            .graphicsLayer { translationY = 56.dp.toPx(); alpha = intro.revealProgress }
            .clip(RoundedCornerShape(28.dp)).bridgeFrostedPanel(backdrop, BridgeFrost)
                .bridgeColorBloom(config.colorTheme),
            shape = RoundedCornerShape(28.dp), color = Color.Transparent,
            border = BridgeGlassEdge) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CAMERA / CONNECT", color = BridgeWhite.copy(alpha = .7f), fontSize = 9.sp, letterSpacing = 1.6.sp)
                val brands = CameraBrand.entries.filter { it.available }
                Text("${(brands.indexOf(config.brand) + 1).coerceAtLeast(1).toString().padStart(2, '0')} / ${brands.size.toString().padStart(2, '0')}",
                    color = BridgeWhite.copy(alpha = .6f), fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
        }
        ConnectionFallingPhotos(photos, hero, intro, heroHeight - 88.dp, config.brand.wordmark, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(horizontal = BridgeDockSideGap).padding(top = 4.dp, bottom = 100.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(R.string.app_name), Modifier.weight(1f), color = BridgeWhite.copy(alpha = .8f),
                    fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                if (!intro.complete) TextButton(intro::skip) { Text("跳过", color = BridgeWhite.copy(alpha = .8f)) }
                IconButton({ intro.skip(); openSettings() }, Modifier.size(48.dp)) {
                    Box(Modifier.size(34.dp).bridgeGlass(CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, "设置", Modifier.size(20.dp), tint = BridgeWhite)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .graphicsLayer { alpha = intro.revealProgress }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (connected) "相机已就绪" else "连接相机", Modifier.weight(1f),
                        color = BridgeWhite.copy(alpha = .7f), fontSize = 11.sp)
                    CameraBrand.entries.filter { it.available }.forEach { brand ->
                        FilterChip(config.brand == brand, { vm.updateConfig { it.copy(brand = brand) } }, enabled = !busy && !connected,
                            label = { Text(brand.wordmark, fontSize = 11.sp) }, shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
                                labelColor = BridgeWhite.copy(alpha = .65f), selectedContainerColor = BridgeWhite.copy(alpha = .2f), selectedLabelColor = BridgeWhite))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                val p = ((intro.revealProgress - .12f) / .88f).coerceIn(0f, 1f)
                alpha = p
                translationY = 32.dp.toPx() * (1f - p)
            }) {
                PhotoDetailChrome(visible = intro.complete || intro.revealProgress > .06f) {
                    if (connected) ConnectionTile(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
                    } else content()
                }
            }
        }
    }
}

@Composable
internal fun ConnectionTile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(24.dp)).bridgeFrostedPanel(null, BridgeFrost, opacity = .5f),
        shape = RoundedCornerShape(24.dp), color = Color.Transparent, contentColor = BridgeWhite,
        border = BridgeGlassEdge, content = content)
}

internal val CameraBrand.wordmark: String get() = when (this) {
    CameraBrand.PANASONIC -> "LUMIX"
    CameraBrand.OTHER -> "CAMERA"
    else -> name
}

/** Keep one random photo and turn that very image into the brand-shaped header. */
@Composable
private fun ConnectionFallingPhotos(photos: List<ConnectionPhoto>, hero: Bitmap?, intro: ConnectionIntroState,
    heroHeight: androidx.compose.ui.unit.Dp, wordmark: String, modifier: Modifier) {
    if (photos.isEmpty()) return
    val chosen = ConnectionLaunchPhotos.heroIndex % photos.size
    BoxWithConstraints(modifier) {
        val availableHeight = maxHeight
        val availableWidth = maxWidth
        if (!intro.complete) repeat(10) { index ->
            if (index != chosen) {
                val bitmap = photos[index % photos.size].bitmap
                Box(Modifier.align(Alignment.TopCenter).width(availableWidth * .29f).aspectRatio(.8f)
                    .clearAndSetSemantics { }.graphicsLayer {
                        val t = ((intro.fallTime - index * 32f) / 580f).coerceIn(0f, 1f)
                        translationX = (((index * 47 + 13) % 101) / 100f - .5f) * availableWidth.toPx() * .9f
                        translationY = -size.height * 1.8f + (availableHeight.toPx() + size.height * 3f) * t * t
                        rotationZ = (index % 2 * 2 - 1) * (12f + index * 2f)
                        alpha = (t * 8f).coerceIn(0f, 1f) * ((1f - t) * 4f).coerceIn(0f, 1f)
                    }.clip(RoundedCornerShape(9.dp)).background(BridgeWhite)
                    .border(1.dp, BridgeWhite.copy(alpha = .9f), RoundedCornerShape(9.dp)).padding(2.dp).clip(RoundedCornerShape(7.dp))) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        val bitmap = hero ?: photos[chosen].bitmap
        Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 28.dp).fillMaxWidth().height(heroHeight)
            .graphicsLayer {
                val t = ((intro.fallTime - chosen * 32f) / 420f).coerceIn(0f, 1f)
                val g = intro.gatherProgress.coerceIn(0f, 1f)
                val initialScale = .36f
                val finalY = 86.dp.toPx()
                val startY = -size.height * 1.8f
                val landingY = availableHeight.toPx() * .39f - size.height / 2f
                val dropY = startY + (landingY - startY) * t * t
                translationY = dropY + (finalY - dropY) * g
                translationX = (((chosen * 47 + 13) % 101) / 100f - .5f) * availableWidth.toPx() * .8f * (1f - g)
                scaleX = initialScale + (1f - initialScale) * g
                scaleY = scaleX
                rotationZ = (if (chosen % 2 == 0) -14f else 14f) * (1f - g)
                alpha = (t * 8f).coerceIn(0f, 1f)
                shadowElevation = 10.dp.toPx() * (1f - g)
                shape = RoundedCornerShape(10.dp)
                clip = true
            }) {
            AnimatedContent(wordmark, Modifier.fillMaxSize(), transitionSpec = {
                (slideInVertically(tween(440)) { it / 5 } + fadeIn(tween(380))) togetherWith
                    (slideOutVertically(tween(380)) { -it / 5 } + fadeOut(tween(300)))
            }, label = "camera brand header") { word ->
                BrandPhotoWordmark(bitmap, word, Modifier.fillMaxSize(),
                    maskFraction = { ((intro.gatherProgress - .3f) / .7f).coerceIn(0f, 1f) })
            }
        }
    }
}

/** The photo is visible only inside the glyphs; no solid text or rectangular image remains. */
@Composable
internal fun BrandPhotoWordmark(bitmap: Bitmap, word: String, modifier: Modifier = Modifier, maskFraction: () -> Float = { 1f }) {
    Box(modifier.semantics { contentDescription = "$word 品牌头图" }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                textSize = 1000f
            }
            val split = (word.length + 1) / 2
            val rows = listOf(word.take(split), word.drop(split)).filter { it.isNotEmpty() }
            val gap = size.height * .035f
            val rowHeight = (size.height * .84f - gap * (rows.size - 1)) / rows.size
            val outlines = rows.map { row ->
                android.graphics.Path().also { textPaint.getTextPath(row, 0, row.length, 0f, 0f, it) }
            }
            val bounds = outlines.map { outline -> android.graphics.RectF().also { outline.computeBounds(it, true) } }
            val scale = minOf(size.width / bounds.maxOf { it.width() }, rowHeight / bounds.maxOf { it.height() })
            val outline = android.graphics.Path()
            outlines.forEachIndexed { index, row ->
                val box = bounds[index]
                val matrix = android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-box.left * scale, size.height * .08f + index * (rowHeight + gap) - box.top * scale)
                }
                row.transform(matrix)
                outline.addPath(row)
            }
            val path = outline.asComposePath()
            val maskPaint = Paint().apply { blendMode = BlendMode.DstIn }
            onDrawWithContent {
                drawContent()
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), maskPaint)
                drawRect(Color.White.copy(alpha = 1f - maskFraction()))
                drawPath(path, Color.White)
                drawContext.canvas.restore()
            }
        }) {
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
