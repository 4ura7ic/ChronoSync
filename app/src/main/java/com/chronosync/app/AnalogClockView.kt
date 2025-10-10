package com.chronosync.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

class AnalogClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentTime: Long = System.currentTimeMillis()
    private var ntpOffset: Long? = null
    private var displayTimeZone: TimeZone = TimeZone.getDefault()
    private var confidenceLevel: Int = 0
    private var secondHandStyle: Int = 0

    // A Rect to store the clickable area of the status text
    private val statusTextBounds = Rect()
    // A lambda function to handle the click event
    private var onStatusTextClick: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val digitalClockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(10f, 0f, 0f, Color.BLACK)
    }
    private val offsetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 50f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 75f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hourHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val minuteHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val secondHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var dayBackgroundColor: Int = 0
    private var nightBackgroundColor: Int = 0
    private var confidenceGreen: Int = 0
    private var confidenceAmber: Int = 0

    init {
        dayBackgroundColor = context.getColor(R.color.day_sky_blue)
        nightBackgroundColor = context.getColor(R.color.night_black)
        confidenceGreen = context.getColor(R.color.sync_green)
        confidenceAmber = context.getColor(R.color.sync_amber)
    }

    fun updateTime(timeInMillis: Long) {
        currentTime = timeInMillis
        invalidate()
    }

    fun setNtpOffset(offset: Long?) {
        ntpOffset = offset
        invalidate()
    }

    fun setConfidenceLevel(level: Int) {
        confidenceLevel = level
        invalidate()
    }

    fun setDisplayTimeZone(timeZoneId: String) {
        displayTimeZone = when (timeZoneId) {
            "UTC" -> TimeZone.getTimeZone("UTC")
            "LOCAL" -> TimeZone.getDefault()
            else -> TimeZone.getTimeZone(timeZoneId)
        }
        invalidate()
    }

    fun setSecondHandStyle(styleIndex: Int) {
        secondHandStyle = styleIndex
        invalidate()
    }

    fun setOnStatusTextClickListener(listener: () -> Unit) {
        onStatusTextClick = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val calendar = Calendar.getInstance(displayTimeZone)
        calendar.timeInMillis = currentTime

        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val isDayTime = hourOfDay in 6..17
        backgroundPaint.color = if (isDayTime) dayBackgroundColor else nightBackgroundColor
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        canvas.drawCircle(centerX, centerY, radius, facePaint)
        drawMinuteTrack(canvas)
        drawNumbers(canvas)

        val hours = calendar.get(Calendar.HOUR_OF_DAY)
        val minutes = calendar.get(Calendar.MINUTE)
        val seconds = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)
        val totalSeconds = minutes * 60 + seconds + millis / 1000f
        val totalHours = hours % 12 + totalSeconds / 3600f
        val hourAngle = (Math.PI / 6 * (totalHours - 3))
        val minuteAngle = (Math.PI / 30 * (totalSeconds / 60f - 15))
        val secondAngle = when (secondHandStyle) {
            0 -> (Math.PI / 30 * (seconds + millis / 1000.0 - 15))
            1 -> (Math.PI / 30 * (seconds - 15))
            else -> {
                val ticksPerSecond = when(secondHandStyle) {
                    2 -> 5.0; 3 -> 6.0; 4 -> 7.0; 5 -> 8.0; 6 -> 10.0
                    else -> 60.0
                }
                val subSecondTick = (millis / 1000.0 * ticksPerSecond).toInt()
                (Math.PI / 30 * (seconds + subSecondTick / ticksPerSecond - 15))
            }
        }
        drawHand(canvas, hourAngle, radius * 0.5f, hourHandPaint)
        drawHand(canvas, minuteAngle, radius * 0.75f, minuteHandPaint)
        drawHand(canvas, secondAngle, radius * 0.9f, secondHandPaint)

        drawDate(canvas)
        drawDigitalClock(canvas)
        drawOffset(canvas)

        val pivotColor = when (confidenceLevel) { 1 -> confidenceGreen; 2 -> confidenceAmber; 3 -> Color.RED; else -> Color.RED }
        pivotPaint.color = pivotColor
        canvas.drawCircle(centerX, centerY, 15f, pivotPaint)
    }

    private fun drawOffset(canvas: Canvas) {
        val padding = 0f
        val xPosition = 0f + padding
        val yPosition = height.toFloat() - padding

        val statusString = if (ntpOffset != null) {
            offsetPaint.color = Color.WHITE
            "Offset: $ntpOffset ms"
        } else {
            offsetPaint.color = Color.YELLOW
            "INTERNAL CLOCK"
        }

        canvas.drawText(statusString, xPosition, yPosition, offsetPaint)

        // Calculate the initial tight bounding box
        offsetPaint.getTextBounds(statusString, 0, statusString.length, statusTextBounds)
        val textHeight = statusTextBounds.height()
        statusTextBounds.offset(xPosition.toInt(), (yPosition - textHeight).toInt())

        // --- NEW: Enlarge the clickable area by 20 pixels on all sides ---
        // Using a negative value with inset() expands the rectangle.
        statusTextBounds.inset(-20, -20)
    }

    private fun drawDate(canvas: Canvas) {
        val dateFormat = SimpleDateFormat("EEEE d", Locale.getDefault())
        dateFormat.timeZone = displayTimeZone
        val dateString = dateFormat.format(Date(currentTime)).uppercase(Locale.getDefault())
        val xPosition = 0f
        val yPosition = 45f
        canvas.drawText(dateString, xPosition, yPosition, datePaint)
    }

    private fun drawDigitalClock(canvas: Canvas) {
        val digitalFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        digitalFormat.timeZone = displayTimeZone
        val digitalString = digitalFormat.format(Date(currentTime))
        val xPosition = centerX
        val yPosition = centerY - (radius * 0.4f)
        canvas.drawText(digitalString, xPosition, yPosition, digitalClockPaint)
    }

    private fun drawMinuteTrack(canvas: Canvas) {
        for (i in 0 until 60) {
            val isHourMark = i % 5 == 0
            val angle = i * (Math.PI / 30)
            val startRadius = if (isHourMark) radius * 0.90f else radius * 0.95f
            val endRadius = radius
            val startX = (centerX + cos(angle) * startRadius).toFloat()
            val startY = (centerY + sin(angle) * startRadius).toFloat()
            val endX = (centerX + cos(angle) * endRadius).toFloat()
            val endY = (centerY + sin(angle) * endRadius).toFloat()
            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }
    }

    private fun drawNumbers(canvas: Canvas) {
        val numberRadius = radius * 0.80f
        for (hour in 1..12) {
            val angle = Math.PI / 6 * (hour - 3)
            val x = (centerX + cos(angle) * numberRadius).toFloat()
            val y = (centerY + sin(angle) * numberRadius + numberPaint.textSize / 3).toFloat()
            canvas.drawText(hour.toString(), x, y, numberPaint)
        }
    }

    private fun drawHand(canvas: Canvas, angle: Double, length: Float, paint: Paint) {
        val endX = (centerX + cos(angle) * length).toFloat()
        val endY = (centerY + sin(angle) * length).toFloat()
        canvas.drawLine(centerX, centerY, endX, endY, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (Math.min(w, h) / 2f) * 0.9f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (statusTextBounds.contains(event.x.toInt(), event.y.toInt())) {
                onStatusTextClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}