/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/


package me.kavishdevar.librepods.presentation.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.res.ResourcesCompat
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Geometry of the spinning case inside R.raw.connected (1050x354, 6.0s 360° turntable loop):
// the case rotates around x = 70% of the video width, its front face is offset by ~7.85% of the
// video width from the rotation axis, and the engraving sits just below the status light at
// ~62.5% of the video height (matching BatteryView's 0.28 vertical bias on the case body).
private const val VIDEO_DURATION_MS = 6000
private const val CASE_WIDTH_FRACTION = 0.395f
private const val CASE_AXIS_X_FRACTION = 0.700f
private const val CASE_DEPTH_RADIUS_FRACTION = 0.0785f
private const val ENGRAVING_Y_FRACTION = 0.625f
private const val EDGE_COS_CUTOFF = 0.12f
private const val EDGE_FADE_RANGE = 0.25f

@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(
    private val context: Context,
    private val onCloseCallback: () -> Unit = {}
) {
    private val mView: View
    private var isClosing = false
    private var autoCloseHandler = Handler(Looper.getMainLooper())
    private var autoCloseRunnable: Runnable? = null
    private var engravingSyncRunnable: Runnable? = null
    private var batteryUpdateReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    private val mParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        height = WindowManager.LayoutParams.WRAP_CONTENT
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        width = if (screenWidthDp >= 600) {
            (400 * displayMetrics.density).toInt()
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dimAmount = 0.3f
        flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private val mWindowManager: WindowManager

    init {
        val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mView = layoutInflater.inflate(R.layout.popup_window, null)
        mParams.x = 0
        mParams.y = 0

        mView.setOnClickListener {
            close()
        }

        mView.findViewById<ImageButton>(R.id.close_button).setOnClickListener {
            close()
        }

        val ll = mView.findViewById<LinearLayout>(R.id.linear_layout)
        ll.setOnClickListener {
            close()
        }

        @Suppress("DEPRECATION")
        mView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        mView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val touchY = event.rawY
                val popupTop = mView.top
                if (touchY < popupTop) {
                    close()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @SuppressLint("InlinedApi", "SetTextI18s")
    fun open(
        name: String = "AirPods Pro",
        engraving: String = "",
        batteryNotification: AirPodsNotifications.BatteryNotification
    ) {
        try {
            if (mView.windowToken == null && mView.parent == null && !isClosing) {
                mView.findViewById<TextView>(R.id.name).text = name

                updateBatteryStatus(batteryNotification)

                val vid = mView.findViewById<VideoView>(R.id.video)
                val caseImage = mView.findViewById<ImageView>(R.id.case_image)
                vid.visibility = View.VISIBLE
                vid.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                vid.setVideoPath("android.resource://me.kavishdevar.librepods/" + R.raw.connected)
                vid.resolveAdjustedSize(vid.width, vid.height)
                vid.start()
                vid.setOnCompletionListener {
                    vid.start()
                }

                if (engraving.isNotBlank()) {
                    startEngravingSync(vid, caseImage, engraving)
                } else {
                    caseImage.visibility = View.GONE
                }

                try {
                    mWindowManager.addView(mView, mParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val displayMetrics = mView.context.resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels

                mView.translationY = screenHeight.toFloat()
                mView.alpha = 1f

                val translationY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, screenHeight.toFloat(), 0f)

                ObjectAnimator.ofPropertyValuesHolder(mView, translationY).apply {
                    duration = 500
                    interpolator = DecelerateInterpolator()
                    start()
                }

                registerBatteryUpdateReceiver()

                autoCloseRunnable = Runnable { close() }
                autoCloseHandler.postDelayed(autoCloseRunnable!!, 12000)
            }
        } catch (e: Exception) {
            Log.e("PopupWindow", "Error opening popup: ${e.message}")
            onCloseCallback()
        }
    }

    private fun startEngravingSync(vid: VideoView, engravingView: ImageView, engraving: String) {
        var engravingBitmap: Bitmap? = null
        var renderedForWidth = 0
        var lastRawVideoPos = -1
        var lastRawVideoPosTimeMs = 0L

        engravingView.alpha = 0f
        engravingView.visibility = View.VISIBLE

        val runnable = object : Runnable {
            override fun run() {
                if (isClosing) return

                val vw = vid.width
                val vh = vid.height
                if (vw > 0 && vh > 0 && vid.isPlaying) {
                    if (engravingBitmap == null || renderedForWidth != vw) {
                        engravingBitmap = renderEngraving(engraving, vw * CASE_WIDTH_FRACTION)
                        engravingView.setImageBitmap(engravingBitmap)
                        renderedForWidth = vw
                    }

                    val duration = vid.duration.takeIf { it > 0 } ?: VIDEO_DURATION_MS
                    val rawPos = vid.currentPosition
                    val now = SystemClock.uptimeMillis()
                    if (rawPos != lastRawVideoPos) {
                        lastRawVideoPos = rawPos
                        lastRawVideoPosTimeMs = now
                    }
                    val elapsed = (now - lastRawVideoPosTimeMs).coerceIn(0L, 100L)
                    val posMs = (rawPos + elapsed) % duration

                    val theta = (posMs.toDouble() / duration.toDouble()) * 2.0 * PI
                    val cosTheta = cos(theta).toFloat()
                    val sinTheta = sin(theta).toFloat()

                    val bmp = engravingBitmap
                    if (bmp != null && cosTheta > EDGE_COS_CUTOFF) {
                        val centerX = vid.left + vw * (CASE_AXIS_X_FRACTION + CASE_DEPTH_RADIUS_FRACTION * sinTheta)
                        val centerY = vid.top + vh * ENGRAVING_Y_FRACTION
                        engravingView.pivotX = bmp.width / 2f
                        engravingView.pivotY = bmp.height / 2f
                        engravingView.translationX = centerX - bmp.width / 2f
                        engravingView.translationY = centerY - bmp.height / 2f
                        engravingView.scaleX = cosTheta
                        engravingView.alpha = ((cosTheta - EDGE_COS_CUTOFF) / EDGE_FADE_RANGE).coerceIn(0f, 1f)
                    } else {
                        engravingView.alpha = 0f
                    }
                } else {
                    engravingView.alpha = 0f
                }

                mView.postOnAnimation(this)
            }
        }
        engravingSyncRunnable = runnable
        mView.postOnAnimation(runnable)
    }

    // Same look and placement as the engraving on the home screen's case (BatteryView): grey,
    // semibold SF Pro Rounded just below the status light, letters about 5% of the case width
    // tall, and emoji in greyscale.
    private fun renderEngraving(engraving: String, caseWidth: Float): Bitmap? {
        if (caseWidth <= 0f) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.sf_pro_rounded)
            color = 0xFFA2A2A4.toInt()
            letterSpacing = 0.06f
            textAlign = Paint.Align.CENTER
            textSize = caseWidth * 0.08f
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val maxWidth = caseWidth * 0.75f
        val measured = paint.measureText(engraving)
        if (measured > maxWidth && measured > 0f) paint.textSize *= maxWidth / measured
        val bmpWidth = maxWidth.toInt().coerceAtLeast(1)
        val textHeight = paint.descent() - paint.ascent()
        val bmpHeight = (textHeight * 1.4f).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val baseline = bmpHeight / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(bitmap).drawText(engraving, bmpWidth / 2f, baseline, paint)
        return bitmap
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBatteryUpdateReceiver() {
        batteryUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.BATTERY_DATA) {
                    val batteryList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra("data", Battery::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra("data")
                    }
                    if (batteryList != null) {
                        updateBatteryStatusFromList(batteryList)
                    }
                }
            }
        }

        val filter = IntentFilter(AirPodsNotifications.BATTERY_DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(batteryUpdateReceiver, filter)
        }
    }

    private fun unregisterBatteryUpdateReceiver() {
        batteryUpdateReceiver?.let {
            try {
                context.unregisterReceiver(it)
                batteryUpdateReceiver = null
            } catch (e: Exception) {
                Log.e("PopupWindow", "Error unregistering battery receiver: ${e.message}")
            }
        }
    }

    private fun updateBatteryStatusFromList(batteryList: List<Battery>) {
        val batteryLeftText = mView.findViewById<TextView>(R.id.left_battery)
        val batteryRightText = mView.findViewById<TextView>(R.id.right_battery)
        val batteryCaseText = mView.findViewById<TextView>(R.id.case_battery)

        batteryLeftText.text = batteryList.find { it.component == BatteryComponent.LEFT }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDC8E    ${it.level}%"
            } else {
                ""
            }
        } ?: ""

        batteryRightText.text = batteryList.find { it.component == BatteryComponent.RIGHT }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDC8D    ${it.level}%"
            } else {
                ""
            }
        } ?: ""

        batteryCaseText.text = batteryList.find { it.component == BatteryComponent.CASE }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDE6C    ${it.level}%"
            } else {
                ""
            }
        } ?: ""
    }

    @SuppressLint("SetTextI18s")
    fun updateBatteryStatus(batteryNotification: AirPodsNotifications.BatteryNotification) {
        val batteryStatus = batteryNotification.getBattery()
        updateBatteryStatusFromList(batteryStatus)
    }

    fun close() {
        try {
            if (isClosing) return
            isClosing = true

            autoCloseRunnable?.let { autoCloseHandler.removeCallbacks(it) }
            engravingSyncRunnable?.let { mView.removeCallbacks(it) }
            engravingSyncRunnable = null
            unregisterBatteryUpdateReceiver()

            val vid = mView.findViewById<VideoView>(R.id.video)
            vid.stopPlayback()

            ObjectAnimator.ofFloat(mView, "translationY", mView.height.toFloat()).apply {
                duration = 500
                interpolator = AccelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            mView.visibility = View.GONE
                            if (mView.parent != null) {
                                mWindowManager.removeView(mView)
                            }
                        } catch (e: Exception) {
                            Log.e("PopupWindow", "Error removing view: ${e.message}")
                        } finally {
                            isClosing = false
                            onCloseCallback()
                        }
                    }
                })
                start()
            }
        } catch (e: Exception) {
            Log.e("PopupWindow", "Error closing popup: ${e.message}")
            isClosing = false
            onCloseCallback()
        }
    }
}
