package com.jaco.musicenhance.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jaco.musicenhance.device.CoverCameraPlacement
import com.jaco.musicenhance.device.CoverDisplayGeometry
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen cover player designed around MIX Flip's two camera cutouts. The camera pill can
 * move with display rotation, and uses reported DisplayCutout bounds when HyperOS exposes them.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
internal class CoverPlayerView(
    context: Context,
    private val controller: PlayerController,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var lastArtwork: Bitmap? = null
    private var lastArtworkGeneration = 0
    private var nativeArtwork: Bitmap? = null
    private var nativeArtworkGeneration = 0
    private var lastArtworkPollAt = 0L
    private var lastControlPollAt = 0L
    private var lastControlState: PlayerControlState? = null
    private var lastSongKey = ""
    private var currentSnapshot = PlayerSnapshot.Empty
    private val artworkView = ImageView(context)
    private val blurredArtworkView = GradientBlurArtworkView(context)
    private val backgroundShade = View(context)
    private val cameraSpectrum = CameraSpectrumView(
        context, controller::bassLevel, controller::setSpectrumPlaybackActive,
    )
    private val infoPanel = LinearLayout(context)
    private val titleView = marqueeText(28f, true, Color.WHITE)
    private val artistView = marqueeText(17f, false, 0xD9FFFFFF.toInt())
    private val progressView = PlayerProgressView(context)
    private val controlsRow = LinearLayout(context)
    private val playButton = PlayerControlView(context, PlayerControlView.Kind.PLAY_PAUSE)
    private val repeatButton = PlayerControlView(context, PlayerControlView.Kind.REPEAT)
    private val favoriteButton = PlayerControlView(context, PlayerControlView.Kind.FAVORITE)
    private val dismissButton = PlayerControlView(context, PlayerControlView.Kind.DISMISS).apply {
        contentDescription = "返回 ${controller.appName}"
        setOnClickListener { onDismiss() }
    }
    private var reportedCutouts: List<Rect> = emptyList()
    private var statusBarTop = 0
    private var lastDisplayRotation = -1
    private var lastLayoutSignature = ""

    private val listener: (PlayerSnapshot) -> Unit = { snapshot ->
        render(snapshot)
    }
    private val ticker = object : Runnable {
        override fun run() {
            val rotation = CoverDisplayGeometry.read(display?.displayId ?: 0)?.rotation
                ?: display?.rotation ?: Surface.ROTATION_0
            if (rotation != lastDisplayRotation) {
                lastDisplayRotation = rotation
                requestApplyInsets()
            }
            // Display id 0 can change its physical panel without changing View dimensions.
            layoutForDisplay()
            val now = SystemClock.elapsedRealtime()
            if (now - lastArtworkPollAt >= ARTWORK_POLL_MS) {
                lastArtworkPollAt = now
                controller.nativeArtwork()?.let(::acceptNativeArtwork)
            }
            if (now - lastControlPollAt >= CONTROL_POLL_MS) {
                lastControlPollAt = now
                applyControlState(controller.controlState())
            }
            if (!isSeeking) render(controller.snapshot())
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    init {
        setBackgroundColor(Color.rgb(32, 32, 34))
        isClickable = true
        isFocusable = true

        artworkView.apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(artworkView, matchParent())
        addView(blurredArtworkView, matchParent())

        backgroundShade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x3D202024, 0x55707072, 0x732A2A2E),
        )
        addView(backgroundShade, matchParent())

        configureInfo()
        configureProgress()
        configureControls()

        addView(cameraSpectrum)
        addView(infoPanel)
        addView(progressView)
        addView(controlsRow)
        addView(dismissButton)
        render(currentSnapshot)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.addListener(listener)
        handler.post(ticker)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        controller.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        reportedCutouts = insets.displayCutout?.boundingRects?.map(::Rect).orEmpty()
        statusBarTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        moduleInfo(
            "Player insets: view=${width}x$height, rotation=${display?.rotation}, cutouts=${reportedCutouts.joinToString()}",
        )
        post(::layoutForDisplay)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post(::layoutForDisplay)
    }

    private fun configureInfo() {
        infoPanel.orientation = LinearLayout.VERTICAL
        infoPanel.gravity = Gravity.CENTER
        infoPanel.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        infoPanel.addView(artistView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun configureProgress() {
        progressView.onTrackingChanged = { isSeeking = it }
        progressView.onSeekRequested = { fraction ->
            val target = (currentSnapshot.durationMs * fraction).toLong()
            if (currentSnapshot.durationMs > 0) {
                controller.seekTo(target)
            }
        }
    }

    private fun configureControls() {
        controlsRow.orientation = LinearLayout.HORIZONTAL
        controlsRow.gravity = Gravity.CENTER
        repeatButton.apply {
            contentDescription = "循环模式"
            setOnClickListener {
                val handled = controller.cycleRepeat()
                if (handled) {
                    lastControlPollAt = 0L
                }
            }
        }
        val previous = PlayerControlView(context, PlayerControlView.Kind.PREVIOUS).apply {
            contentDescription = "上一首"
            setOnClickListener { controller.previous() }
        }
        playButton.apply {
            contentDescription = "播放或暂停"
            setOnClickListener { controller.playPause() }
        }
        val next = PlayerControlView(context, PlayerControlView.Kind.NEXT).apply {
            contentDescription = "下一首"
            setOnClickListener { controller.next() }
        }
        favoriteButton.apply {
            contentDescription = "收藏"
            setOnClickListener {
                val handled = controller.toggleFavorite()
                if (handled) {
                    lastControlPollAt = 0L
                }
            }
        }
        listOf(repeatButton, previous, playButton, next, favoriteButton).forEach { button ->
            controlsRow.addView(button, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun layoutForDisplay() {
        if (width <= 0 || height <= 0) return
        val portrait = height > width * 1.03f
        val physical = CoverDisplayGeometry.read(display?.displayId ?: 0)?.takeIf {
            min(it.width, it.height).toFloat() / max(it.width, it.height).coerceAtLeast(1) >= 0.60f
        }
        val localRotation = display?.rotation ?: Surface.ROTATION_0
        val rotation = physical?.rotation ?: localRotation
        fun Rect.cameraBounds(frameWidth: Int, frameHeight: Int) = CoverCameraPlacement.Cutout(
            left, top, right, bottom, frameWidth, frameHeight,
        )
        val cameraCutout = physical?.cutout
        val windowCutout = reportedCutouts.map { it.cameraBounds(width, height) }
            .firstOrNull { it.isCamera() }
        val camera = CoverCameraPlacement.resolve(
            cameraCutout?.cameraBounds(physical.width, physical.height), windowCutout, rotation,
        )
        val cameraRight = camera.right
        val cameraBottom = camera.bottom
        val layoutSignature = "$width:$height:physical=$rotation:local=$localRotation:" +
            "cutout=$cameraCutout:window=$reportedCutouts:source=${camera.source}:status=$statusBarTop:$cameraRight:$cameraBottom"
        if (layoutSignature == lastLayoutSignature) return
        lastLayoutSignature = layoutSignature
        moduleInfo("Player layout: $layoutSignature")

        if (portrait) {
            // Pixel-matched to NetEase Cloud Music on MIX Flip 2 (1208x1392 reference):
            // x=838..1178, y=661..1362, size=341x702, outer-edge inset=29px.
            // Prefer raw DisplayInfo; window cutout covers reverse-home launches with no raw cutout.
            val pillW = (width * (341f / 1208f)).toInt()
            val pillH = (height * (702f / 1392f)).toInt()
            val edgeX = (width * (29f / 1208f)).toInt()
            val edgeY = (height * (29f / 1392f)).toInt()
            setFrame(
                cameraSpectrum,
                if (cameraRight) width - edgeX - pillW else edgeX,
                if (cameraBottom) height - edgeY - pillH else edgeY,
                pillW,
                pillH,
            )
        } else {
            val pillW = (width * 0.245f).toInt()
            val pillH = (height * 0.58f).toInt()
            setFrame(
                cameraSpectrum,
                if (cameraRight) width - pillW - (width * 0.021f).toInt() else (width * 0.021f).toInt(),
                (height * 0.024f).toInt(),
                pillW,
                pillH,
            )
        }

        run {
            dismissButton.rotation = if (cameraRight) 0f else 180f
            val buttonSize = min(width, height) * 0.075f
            val statusHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusHeight = max(statusBarTop, if (statusHeightId != 0) resources.getDimensionPixelSize(statusHeightId) else dp(24))
            setFrame(
                dismissButton,
                if (cameraRight) (width * 0.025f).toInt() else (width - buttonSize - width * 0.025f).toInt(),
                max((height * 0.025f).toInt(), statusHeight + dp(6)),
                buttonSize.toInt(),
                buttonSize.toInt(),
            )
        }

        if (!portrait) {
            blurredArtworkView.blurTowardTop = true
            val contentX = if (cameraRight) (width * 0.055f).toInt() else (width * 0.33f).toInt()
            val contentW = (width * 0.62f).toInt()
            setFrame(infoPanel, contentX, (height * 0.065f).toInt(), contentW, (height * 0.30f).toInt())
            setFrame(progressView, contentX, (height * 0.46f).toInt(), contentW, dp(42))
            setFrame(controlsRow, contentX - dp(8), (height * 0.60f).toInt(), contentW + dp(16), (height * 0.22f).toInt())
        } else if (cameraRight) {
            blurredArtworkView.blurTowardTop = true
            setFrame(infoPanel, (width * 0.07f).toInt(), (height * 0.07f).toInt(), (width * 0.86f).toInt(), (height * 0.19f).toInt())
            setFrame(progressView, (width * 0.07f).toInt(), (height * 0.27f).toInt(), (width * 0.86f).toInt(), dp(42))
            setFrame(controlsRow, (width * 0.07f).toInt(), (height * 0.35f).toInt(), (width * 0.86f).toInt(), (height * 0.15f).toInt())
        } else {
            blurredArtworkView.blurTowardTop = false
            setFrame(infoPanel, (width * 0.07f).toInt(), (height * 0.53f).toInt(), (width * 0.86f).toInt(), (height * 0.19f).toInt())
            setFrame(progressView, (width * 0.07f).toInt(), (height * 0.74f).toInt(), (width * 0.86f).toInt(), dp(42))
            setFrame(controlsRow, (width * 0.07f).toInt(), (height * 0.82f).toInt(), (width * 0.86f).toInt(), (height * 0.14f).toInt())
        }
    }

    fun refreshDisplayLayout() {
        lastDisplayRotation = display?.rotation ?: Surface.ROTATION_0
        layoutForDisplay()
    }

    private fun render(snapshot: PlayerSnapshot) {
        currentSnapshot = snapshot
        val songKey = "${snapshot.title}\u0000${snapshot.artist}"
        if (songKey != lastSongKey) {
            lastSongKey = songKey
            nativeArtwork = null
            nativeArtworkGeneration = 0
            lastArtworkPollAt = 0L
            lastControlPollAt = 0L
            favoriteButton.active = false
            favoriteButton.isEnabled = false
        }
        titleView.text = snapshot.title
        artistView.text = snapshot.artist
        playButton.playing = snapshot.isPlaying
        cameraSpectrum.playing = snapshot.isPlaying
        if (!isSeeking) {
            val fraction = if (snapshot.durationMs > 0) snapshot.positionMs.toDouble() / snapshot.durationMs else 0.0
            progressView.fraction = fraction.toFloat().coerceIn(0f, 1f)
        }
        progressView.isEnabled = snapshot.durationMs > 0

        val highResolutionArtwork = controller.highResolutionArtwork(snapshot)
        val artwork = listOfNotNull(highResolutionArtwork, snapshot.artwork, nativeArtwork)
            .maxByOrNull { min(it.width, it.height) }
        val generation = artwork?.generationId ?: 0
        if (lastArtwork !== artwork || lastArtworkGeneration != generation) {
            lastArtwork = artwork
            lastArtworkGeneration = generation
            if (artwork != null) {
                moduleInfo("Player artwork selected: ${artwork.width}x${artwork.height}, hd=${artwork === highResolutionArtwork}")
                artworkView.setImageBitmap(artwork)
                blurredArtworkView.artwork = artwork
                cameraSpectrum.spectrumColor = dominantArtworkColor(artwork)
            } else {
                blurredArtworkView.artwork = null
                cameraSpectrum.spectrumColor = DEFAULT_SPECTRUM_COLOR
                artworkView.setImageDrawable(
                    GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFFB9C2C4.toInt(), 0xFF7C858D.toInt(), 0xFF4C5159.toInt()),
                    ),
                )
            }
        }
    }

    private fun acceptNativeArtwork(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val shortSide = min(bitmap.width, bitmap.height)
        val longSide = max(bitmap.width, bitmap.height)
        if (shortSide < 400 || shortSide.toFloat() / longSide < 0.72f) return
        val generation = bitmap.generationId
        if (nativeArtwork === bitmap && nativeArtworkGeneration == generation) return
        nativeArtwork = bitmap
        nativeArtworkGeneration = generation
        moduleInfo("Using ${controller.appName} native artwork: ${bitmap.width}x${bitmap.height}")
        render(currentSnapshot)
    }

    private fun applyControlState(state: PlayerControlState) {
        if (state != lastControlState) {
            lastControlState = state
            moduleInfo("${controller.appName} native controls: repeat=${state.repeatMode}, favorite=${state.favorite}")
        }
        val favorite = state.favorite.takeIf { state.songTitle == currentSnapshot.title }
        favoriteButton.isEnabled = favorite != null
        favoriteButton.contentDescription = when (favorite) {
            true -> "取消喜欢"
            false -> "喜欢"
            null -> "正在读取喜欢状态"
        }
        favorite?.let { favoriteButton.active = it }
        repeatButton.repeatMode = state.repeatMode
        repeatButton.active = state.repeatMode in setOf(
            RepeatMode.LIST_LOOP,
            RepeatMode.SINGLE_LOOP,
            RepeatMode.SHUFFLE,
        )
        repeatButton.contentDescription = when (state.repeatMode) {
            RepeatMode.LIST_LOOP -> "列表循环"
            RepeatMode.SINGLE_LOOP -> "单曲循环"
            RepeatMode.SHUFFLE -> "随机播放"
            RepeatMode.SEQUENTIAL -> "顺序播放"
            RepeatMode.UNKNOWN -> "循环模式"
        }
    }

    private fun marqueeText(sizeSp: Float, bold: Boolean, color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
        includeFontPadding = false
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x55000000)
    }

    private fun setFrame(view: View, x: Int, y: Int, w: Int, h: Int) {
        view.layoutParams = LayoutParams(w.coerceAtLeast(1), h.coerceAtLeast(1)).apply {
            leftMargin = x
            topMargin = y
        }
    }

    private fun matchParent() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dominantArtworkColor(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_SPECTRUM_COLOR
        val hueWeights = FloatArray(24)
        val redSums = FloatArray(24)
        val greenSums = FloatArray(24)
        val blueSums = FloatArray(24)
        val hsv = FloatArray(3)
        val stepX = (bitmap.width / 36).coerceAtLeast(1)
        val stepY = (bitmap.height / 36).coerceAtLeast(1)
        var fallbackRed = 0f
        var fallbackGreen = 0f
        var fallbackBlue = 0f
        var fallbackWeight = 0f

        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 160) {
                    Color.colorToHSV(pixel, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (value in 0.10f..0.96f) {
                        val weight = (0.30f + saturation * 0.70f) * (0.45f + value * 0.55f)
                        fallbackRed += Color.red(pixel) * weight
                        fallbackGreen += Color.green(pixel) * weight
                        fallbackBlue += Color.blue(pixel) * weight
                        fallbackWeight += weight
                        if (saturation >= 0.10f) {
                            val bin = (hsv[0] / 15f).toInt().coerceIn(0, hueWeights.lastIndex)
                            hueWeights[bin] += weight
                            redSums[bin] += Color.red(pixel) * weight
                            greenSums[bin] += Color.green(pixel) * weight
                            blueSums[bin] += Color.blue(pixel) * weight
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val dominantBin = hueWeights.indices.maxByOrNull { hueWeights[it] } ?: 0
        val dominantWeight = hueWeights[dominantBin]
        val color = if (dominantWeight > 0f) {
            Color.rgb(
                (redSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
                (greenSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
                (blueSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
            )
        } else if (fallbackWeight > 0f) {
            Color.rgb(
                (fallbackRed / fallbackWeight).toInt().coerceIn(0, 255),
                (fallbackGreen / fallbackWeight).toInt().coerceIn(0, 255),
                (fallbackBlue / fallbackWeight).toInt().coerceIn(0, 255),
            )
        } else {
            return DEFAULT_SPECTRUM_COLOR
        }

        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.12f) hsv[1] = 0.08f else hsv[1] = hsv[1].coerceAtLeast(0.42f)
        hsv[2] = hsv[2].coerceIn(0.72f, 0.98f)
        return Color.HSVToColor(hsv)
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val UI_TICK_MS = 50L
        const val ARTWORK_POLL_MS = 1_500L
        const val CONTROL_POLL_MS = 200L
        const val DEFAULT_SPECTRUM_COLOR = 0xFFE82A1F.toInt()
    }
}
