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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(
    private val context: Context,
    private val onCloseCallback: () -> Unit = {}
) {
    private val mView: View
    private var isClosing = false
    private var autoCloseHandler = Handler(Looper.getMainLooper())
    private var autoCloseRunnable: Runnable? = null
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
        caseRes: Int = R.drawable.airpods_pro_2_case,
        batteryNotification: AirPodsNotifications.BatteryNotification
    ) {
        try {
            if (mView.windowToken == null && mView.parent == null && !isClosing) {
                mView.findViewById<TextView>(R.id.name).text = name

                updateBatteryStatus(batteryNotification)

                val vid = mView.findViewById<VideoView>(R.id.video)
                val caseImage = mView.findViewById<ImageView>(R.id.case_image)
                val engravedCase = if (engraving.isNotBlank()) engraveCase(caseRes, engraving) else null
                if (engravedCase != null) {
                    // The video spins the case, so the engraving can't be drawn on it. Show the
                    // case front with the engraving instead, popping in like the video does.
                    vid.visibility = View.GONE
                    caseImage.setImageBitmap(engravedCase)
                    caseImage.visibility = View.VISIBLE
                    caseImage.scaleX = 0.85f
                    caseImage.scaleY = 0.85f
                    caseImage.alpha = 0f
                    caseImage.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setStartDelay(250).setDuration(600)
                        .setInterpolator(DecelerateInterpolator()).start()
                } else {
                    caseImage.visibility = View.GONE
                    vid.visibility = View.VISIBLE
                    vid.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                    vid.setVideoPath("android.resource://me.kavishdevar.librepods/" + R.raw.connected)
                    vid.resolveAdjustedSize(vid.width, vid.height)
                    vid.start()
                    vid.setOnCompletionListener {
                        vid.start()
                    }
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

    // Same look and placement as the engraving on the home screen's case (BatteryView): grey,
    // semibold SF Pro just below the status light, letters about 5% of the case width tall, and
    // emoji in greyscale.
    private fun engraveCase(caseRes: Int, engraving: String): Bitmap? {
        val case = BitmapFactory.decodeResource(context.resources, caseRes) ?: return null
        val bitmap = case.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.sf_pro)
            isFakeBoldText = true
            color = 0xFFA2A2A4.toInt()
            letterSpacing = 0.06f
            textAlign = Paint.Align.CENTER
            textSize = bitmap.width * 0.08f
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val maxWidth = bitmap.width * 0.75f
        val measured = paint.measureText(engraving)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
        val centerY = bitmap.height / 2f * (1f + 0.28f)
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        Canvas(bitmap).drawText(engraving, bitmap.width / 2f, baseline, paint)
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
