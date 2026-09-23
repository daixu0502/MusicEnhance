package com.jaco.musicenhance.player.ui

import android.animation.ValueAnimator
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
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jaco.musicenhance.device.CoverCameraPlacement
import com.jaco.musicenhance.device.CoverDisplayGeometry
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.PlayerSystemBars
import com.jaco.musicenhance.player.artwork.ArtworkPalette
import com.jaco.musicenhance.player.artwork.ArtworkDimensions
import com.jaco.musicenhance.player.artwork.PlayerArtworkState
import com.jaco.musicenhance.player.artwork.ArtworkTransition
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import com.jaco.musicenhance.player.ui.lyrics.LyricsView
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen cover player designed around MIX Flip's two camera cutouts. The camera pill can
 * move with display rotation, and uses reported DisplayCutout bounds when HyperOS exposes them.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
internal class CoverPlayerView(
    context: Context,
    window: Window,
    private val controller: PlayerController,
    private val keepScreenOnRequested: () -> Boolean = { false },
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val systemBars = PlayerSystemBars(window)
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var isClosing = false
    private val artworkState = PlayerArtworkState()
    private val artworkTransition = ArtworkTransition()
    private var thumbnailArtwork: Bitmap? = null
    private var thumbnailGeneration = 0
    private var displayedArtwork: Bitmap? = null
    private var displayedArtworkGeneration = 0
    private var nativeArtwork: Bitmap? = null
    private var nativeArtworkGeneration = 0
    private var lastArtworkPollAt = 0L
    private var lastControlPollAt = 0L
    private var lastControlState: PlayerControlState? = null
    private var lastMetadataKey = ""
    private var currentSnapshot = PlayerSnapshot.Empty
    private val artworkView = ImageView(context)
    private val blurredArtworkView = GradientBlurArtworkView(context)
    private val backgroundShade = View(context)
    private val lyricsShade = View(context).apply { setBackgroundColor(0x26000000) }
    private val lyricsView = LyricsView(context)
    private val lyricsRail = View(context)
    private val railBackground = GradientDrawable().apply { setColor(Color.BLACK) }
    private val lyricsHeader = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val lyricsTitleView = marqueeText(24f, true, Color.WHITE)
    private val lyricsArtistView = marqueeText(16f, false, 0xBFFFFFFF.toInt())
    private val lyricsFavoriteButton = PlayerControlView(context, PlayerControlView.Kind.FAVORITE)
    private val lyricsPlayButton = PlayerControlView(context, PlayerControlView.Kind.PLAY_PAUSE)
    private val lyricsArtworkView = ImageView(context)
    private var isLyricsMode = false
    private var lyricsTransitionProgress = 0f
    private var modeTransitionAnimator: ValueAnimator? = null
    private val cameraFrame = Rect()
    private val normalHeaderFrame = Rect()
    private val lyricsHeaderFrame = Rect()
    private var cameraOnBottom = true
    private var isBackgroundTouch = false
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
    // init calls applyModeLayout(), so these groups must be ready before the init block runs.
    private val normalModeViews = listOf(progressView, controlsRow)
    private val lyricsModeViews = listOf(lyricsHeader, lyricsView, lyricsFavoriteButton, lyricsPlayButton, lyricsArtworkView)
    private val functionalViews = listOf(progressView, controlsRow, dismissButton, lyricsFavoriteButton, lyricsPlayButton, lyricsArtworkView)
    private var reportedCutouts: List<Rect> = emptyList()
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
            // Establish the current song before associating a native cover with this frame.
            if (!isSeeking) render(controller.snapshot())
            if (now - lastArtworkPollAt >= ARTWORK_POLL_MS) {
                lastArtworkPollAt = now
                controller.nativeArtwork()?.let(::acceptNativeArtwork)
            }
            if (now - lastControlPollAt >= CONTROL_POLL_MS) {
                lastControlPollAt = now
                refreshKeepScreenOn()
                applyControlState(controller.controlState())
            }
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    init {
        setBackgroundColor(Color.rgb(32, 32, 34))
        isClickable = true
        isFocusable = true
        contentDescription = "点击空白处切换歌词"
        setOnClickListener { setLyricsMode(!isLyricsMode) }

        artworkView.apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(artworkView, matchParent())
        addView(blurredArtworkView, matchParent())

        backgroundShade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x3D202024, 0x55707072, 0x732A2A2E),
        )
        addView(backgroundShade, matchParent())
        addView(lyricsShade, matchParent())

        configureInfo()
        configureProgress()
        configureControls()

        configureLyrics()
        addView(lyricsRail)
        addView(cameraSpectrum)
        addView(infoPanel)
        addView(progressView)
        addView(controlsRow)
        addView(lyricsHeader)
        addView(lyricsView)
        addView(lyricsFavoriteButton)
        addView(lyricsPlayButton)
        addView(lyricsArtworkView)
        addView(dismissButton)
        applyModeLayout()
        render(currentSnapshot)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isClosing = false
        refreshKeepScreenOn()
        systemBars.start()
        controller.addListener(listener)
        handler.post(ticker)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        keepScreenOn = false
        systemBars.stop()
        handler.removeCallbacks(ticker)
        controller.removeListener(listener)
        controller.release()
        modeTransitionAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshKeepScreenOn() else keepScreenOn = false
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) refreshKeepScreenOn() else keepScreenOn = false
    }

    private fun refreshKeepScreenOn() {
        // View-owned flags leave the host's existing window flags untouched and stop holding
        // the display as soon as this overlay is removed/hidden. Never dismiss the keyguard.
        keepScreenOn = isAttachedToWindow && !isClosing && isShown &&
            windowVisibility == VISIBLE && hasWindowFocus() && keepScreenOnRequested()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        reportedCutouts = insets.displayCutout?.boundingRects?.map(::Rect).orEmpty()
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

    private fun configureLyrics() {
        lyricsRail.background = railBackground
        listOf(lyricsTitleView, lyricsArtistView).forEach { label ->
            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            label.textAlignment = TEXT_ALIGNMENT_GRAVITY
            lyricsHeader.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (label === lyricsArtistView) topMargin = dp(6)
            })
        }
        lyricsView.onSeekRequested = { time ->
            val snapshot = controller.snapshot()
            if (snapshot.metadataKey == currentSnapshot.metadataKey && snapshot.durationMs > 0) {
                controller.seekAndPlay(time.coerceIn(0, snapshot.durationMs))
            }
        }
        lyricsView.onBackgroundClick = { setLyricsMode(false) }
        lyricsPlayButton.contentDescription = "播放或暂停"
        lyricsPlayButton.setOnClickListener { controller.playPause() }
        lyricsFavoriteButton.setOnClickListener { toggleFavorite() }
        lyricsArtworkView.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(0xFF444448.toInt()); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
            contentDescription = "返回封面播放器"
            isFocusable = true
            setOnClickListener { setLyricsMode(false) }
        }
    }

    private fun setLyricsMode(enabled: Boolean) {
        isLyricsMode = enabled
        modeTransitionAnimator?.cancel()
        if (enabled) lyricsView.render(controller.lyrics(currentSnapshot), currentSnapshot.positionMs)
        modeTransitionAnimator = ValueAnimator.ofFloat(lyricsTransitionProgress, if (enabled) 1f else 0f).apply {
            duration = MODE_TRANSITION_DURATION_MS
            interpolator = PathInterpolator(0.22f, 0f, 0.2f, 1f)
            addUpdateListener {
                lyricsTransitionProgress = it.animatedValue as Float
                applyModeLayout()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Disabled controls must not behave as a tap on the background.
            val occupied = functionalViews
                .any { it.visibility == VISIBLE && event.x >= it.left && event.x <= it.right && event.y >= it.top && event.y <= it.bottom }
            isBackgroundTouch = !occupied
        }
        return if (isBackgroundTouch) super.onTouchEvent(event) else true
    }

    override fun performClick(): Boolean = super.performClick()

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
            setOnClickListener { toggleFavorite() }
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
            "cutout=$cameraCutout:window=$reportedCutouts:source=${camera.source}:$cameraRight:$cameraBottom"
        if (layoutSignature == lastLayoutSignature) return
        lastLayoutSignature = layoutSignature
        cameraOnBottom = cameraBottom
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
            val buttonSize = (min(width, height) * 0.075f).toInt()
            val cornerInset = (width * 0.025f).toInt()
            setFrame(
                dismissButton,
                if (cameraRight) cornerInset else width - buttonSize - cornerInset,
                cornerInset,
                buttonSize,
                buttonSize,
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
        copyFrame(cameraSpectrum, cameraFrame)
        copyFrame(infoPanel, normalHeaderFrame)
        val edge = (width * 0.048f).toInt()
        val textLeft = if (cameraRight) edge else cameraFrame.right + edge
        val textRight = if (cameraRight) cameraFrame.left - edge else width - edge
        val dismissFrame = Rect().also { copyFrame(dismissButton, it) }
        // Equal top/side corner insets; the two title lines move up with the back button.
        // In reverse orientation, reserve the mirrored right corner instead.
        val headingTop = dismissFrame.top
        val headingHeight = max(dp(66), (height * 0.13f).toInt())
        val headingLeft = if (cameraRight) dismissFrame.right + dp(8) else textLeft
        val headingRight = if (cameraRight) textRight else dismissFrame.left - dp(8)
        lyricsHeaderFrame.set(headingLeft, headingTop, headingRight, headingTop + headingHeight)
        setFrame(lyricsView, textLeft, lyricsHeaderFrame.bottom + dp(6), textRight - textLeft,
            height - lyricsHeaderFrame.bottom - dp(18))
        applyModeLayout()
    }

    /** Only presentation changes here: never resize the calibrated camera/spectrum view. */
    private fun applyModeLayout() {
        val transitionProgress = lyricsTransitionProgress
        blurredArtworkView.fullBlurProgress = transitionProgress
        lyricsShade.alpha = transitionProgress
        val normalAlpha = (1f - transitionProgress * 1.7f).coerceIn(0f, 1f)
        normalModeViews.forEach {
            it.alpha = normalAlpha
            it.visibility = if (normalAlpha > 0f) VISIBLE else INVISIBLE
        }
        infoPanel.alpha = 1f - transitionProgress
        val lyricsAlpha = ((transitionProgress - 0.22f) / 0.78f).coerceIn(0f, 1f)
        lyricsModeViews.forEach {
            it.alpha = lyricsAlpha
            it.visibility = if (lyricsAlpha > 0f) VISIBLE else INVISIBLE
        }
        lyricsView.translationY = (1f - transitionProgress) * dp(if (cameraOnBottom) 12 else -12)
        if (cameraFrame.isEmpty || normalHeaderFrame.isEmpty) return
        fun mix(a: Int, b: Int) = (a + (b - a) * transitionProgress).toInt()
        val infoX = mix(normalHeaderFrame.left, lyricsHeaderFrame.left)
        val infoY = mix(normalHeaderFrame.top, lyricsHeaderFrame.top)
        val infoW = mix(normalHeaderFrame.width(), lyricsHeaderFrame.width())
        val infoH = mix(normalHeaderFrame.height(), lyricsHeaderFrame.height())
        setFrame(infoPanel, infoX, infoY, infoW, infoH)
        setFrame(lyricsHeader, infoX, infoY, infoW, infoH)
        val edgeY = (height * 29f / 1392f).toInt()
        val top = if (cameraOnBottom) mix(cameraFrame.top, edgeY) else cameraFrame.top
        val bottom = if (cameraOnBottom) cameraFrame.bottom else mix(cameraFrame.bottom, height - edgeY)
        railBackground.cornerRadius = cameraFrame.width() / 2f
        setFrame(lyricsRail, cameraFrame.left, top, cameraFrame.width(), bottom - top)
        val freeTop = if (cameraOnBottom) edgeY else cameraFrame.bottom
        val freeBottom = if (cameraOnBottom) cameraFrame.top else height - edgeY
        val freeHeight = (freeBottom - freeTop).coerceAtLeast(1)
        val buttonSize = min(cameraFrame.width() * 0.62f, freeHeight * 0.25f).toInt()
        val imageSize = min(cameraFrame.width() * 0.47f, freeHeight * 0.25f).toInt()
        // Mirror the order as well as the extension: thumbnail is always nearest the cameras.
        fun railItem(view: View, fraction: Float, size: Int) {
            val centerY = freeTop + freeHeight * (if (cameraOnBottom) fraction else 1f - fraction)
            setFrame(view, cameraFrame.centerX() - size / 2, (centerY - size / 2).toInt(), size, size)
            // Reveal controls only after the expanding black surface reaches their whole bounds.
            val clearance = if (cameraOnBottom) centerY - size / 2 - top else bottom - centerY - size / 2
            view.alpha = lyricsAlpha * (clearance / (size * 0.4f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            view.visibility = if (view.alpha > 0f) VISIBLE else INVISIBLE
        }
        railItem(lyricsFavoriteButton, 0.20f, buttonSize)
        railItem(lyricsPlayButton, 0.47f, buttonSize)
        railItem(lyricsArtworkView, 0.78f, imageSize)
    }

    private fun copyFrame(view: View, out: Rect) {
        val frame = view.layoutParams as LayoutParams
        out.set(frame.leftMargin, frame.topMargin, frame.leftMargin + frame.width, frame.topMargin + frame.height)
    }

    fun refreshDisplayLayout() {
        lastDisplayRotation = display?.rotation ?: Surface.ROTATION_0
        layoutForDisplay()
    }

    /** Release foreground-only policies before the outgoing Activity's surface is detached. */
    fun prepareForDismissal() {
        isClosing = true
        keepScreenOn = false
        // This Activity is finishing; showing bars on its outgoing surface causes a flash.
        systemBars.stop(restoreVisibility = false)
    }

    private fun render(snapshot: PlayerSnapshot) {
        currentSnapshot = snapshot
        if (artworkState.updateTrack(snapshot)) {
            artworkTransition.begin(displayedArtwork, SystemClock.elapsedRealtime())
            nativeArtwork = null
            nativeArtworkGeneration = 0
            lastArtworkPollAt = 0L
        }
        val metadataKey = snapshot.metadataKey
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            lastControlPollAt = 0L
            favoriteButton.active = false
            favoriteButton.isEnabled = false
            lyricsFavoriteButton.active = false
            lyricsFavoriteButton.isEnabled = false
        }
        if (titleView.text.toString() != snapshot.title) titleView.text = snapshot.title
        if (artistView.text.toString() != snapshot.artist) artistView.text = snapshot.artist
        if (lyricsTitleView.text.toString() != snapshot.title) lyricsTitleView.text = snapshot.title
        if (lyricsArtistView.text.toString() != snapshot.artist) lyricsArtistView.text = snapshot.artist
        playButton.playing = snapshot.isPlaying
        lyricsPlayButton.playing = snapshot.isPlaying
        lyricsPlayButton.contentDescription = if (snapshot.isPlaying) "暂停" else "播放"
        if (isLyricsMode || lyricsTransitionProgress > 0f) lyricsView.render(controller.lyrics(snapshot), snapshot.positionMs)
        cameraSpectrum.playing = snapshot.isPlaying
        if (!isSeeking) {
            val fraction = if (snapshot.durationMs > 0) snapshot.positionMs.toDouble() / snapshot.durationMs else 0.0
            progressView.fraction = fraction.toFloat().coerceIn(0f, 1f)
        }
        progressView.isEnabled = snapshot.durationMs > 0

        renderArtwork(snapshot)
    }

    private fun renderArtwork(snapshot: PlayerSnapshot) {
        val verifiedArtwork = controller.verifiedArtwork(snapshot)
        val selectedArtwork = artworkState.select(verifiedArtwork, snapshot.artwork, nativeArtwork)
        val selectedGeneration = selectedArtwork?.generationId ?: 0
        if (thumbnailArtwork !== selectedArtwork || thumbnailGeneration != selectedGeneration) {
            thumbnailArtwork = selectedArtwork
            thumbnailGeneration = selectedGeneration
            lyricsArtworkView.setImageBitmap(selectedArtwork)
        }
        val currentArtworkReady = verifiedArtwork?.isRecycled == false ||
            (!controller.hasArtworkProvider && selectedArtwork != null)
        val artwork = artworkTransition.background(
            selectedArtwork, currentArtworkReady, SystemClock.elapsedRealtime(),
        )
        val generation = artwork?.generationId ?: 0
        if (displayedArtwork !== artwork || displayedArtworkGeneration != generation) {
            displayedArtwork = artwork
            displayedArtworkGeneration = generation
            if (artwork != null) {
                moduleInfo("Player artwork selected: ${artwork.width}x${artwork.height}, verified=${artwork === verifiedArtwork}")
                artworkView.setImageBitmap(artwork)
                blurredArtworkView.artwork = artwork
                cameraSpectrum.spectrumColor = ArtworkPalette.spectrumColor(artwork)
            } else {
                blurredArtworkView.artwork = null
                cameraSpectrum.spectrumColor = ArtworkPalette.DEFAULT_SPECTRUM_COLOR
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
        if (!ArtworkDimensions.isUsable(bitmap.width, bitmap.height)) return
        val generation = bitmap.generationId
        if (nativeArtwork === bitmap && nativeArtworkGeneration == generation) return
        nativeArtwork = bitmap
        nativeArtworkGeneration = generation
        moduleInfo("Using ${controller.appName} native artwork: ${bitmap.width}x${bitmap.height}")
        render(currentSnapshot)
    }

    private fun toggleFavorite() {
        if (controller.toggleFavorite()) lastControlPollAt = 0L
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
        lyricsFavoriteButton.isEnabled = favoriteButton.isEnabled
        lyricsFavoriteButton.active = favoriteButton.active
        lyricsFavoriteButton.contentDescription = favoriteButton.contentDescription
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
        val old = view.layoutParams as? LayoutParams
        if (old != null && old.leftMargin == x && old.topMargin == y && old.width == w.coerceAtLeast(1) && old.height == h.coerceAtLeast(1)) return
        view.layoutParams = LayoutParams(w.coerceAtLeast(1), h.coerceAtLeast(1)).apply {
            leftMargin = x
            topMargin = y
        }
    }

    private fun matchParent() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val UI_TICK_MS = 50L
        const val ARTWORK_POLL_MS = 1_500L
        const val CONTROL_POLL_MS = 200L
        const val MODE_TRANSITION_DURATION_MS = 500L
    }
}
