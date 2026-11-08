package uk.co.channelbox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import java.util.Date

/**
 * A Sky-style TV guide grid: channels down the left, time across the top,
 * programmes as blocks. Drawn by hand so it stays quick with thousands of
 * channels, and moved around entirely with the remote.
 */
class EpgGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** The highlight moved. [programme] is null where there are no listings. */
        fun onGuideFocus(row: Int, programme: Programme?, slotStart: Long)
        fun onGuideSelect(row: Int, programme: Programme?)
    }

    var listener: Listener? = null
    var timeFormat: java.text.DateFormat = android.text.format.DateFormat.getTimeFormat(context)

    private var channels: List<Channel> = emptyList()
    private var guide: GuideData = GuideData.EMPTY
    var playingId: String? = null
        set(value) { field = value; invalidate() }

    // Layout sizes
    private val channelColumn = dp(210f)
    private val headerHeight = dp(30f)
    private val rowHeight = dp(50f)
    private val gap = dp(3f)
    private val pad = dp(10f)
    private val radius = dp(6f)

    // Where we are
    private var windowStart = 0L
    private var firstRow = 0
    var focusRow = 0
        private set
    private var focusTime = 0L

    // Paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f); color = Color.WHITE }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(14f); color = COLOR_DIM }
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); color = COLOR_DIM }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---------------------------------------------------------------- data

    fun setData(channels: List<Channel>, guide: GuideData, startRow: Int) {
        this.channels = channels
        this.guide = guide
        val now = System.currentTimeMillis()
        windowStart = floorSlot(now)
        focusTime = now
        focusRow = if (channels.isEmpty()) 0 else startRow.coerceIn(0, channels.size - 1)
        firstRow = 0
        keepFocusVisible(centre = true)
        invalidate()
        notifyFocus()
    }

    fun updateGuide(guide: GuideData) {
        this.guide = guide
        invalidate()
        notifyFocus()
    }

    /** Called every minute so the "now" line moves along. */
    fun tick() {
        val earliest = floorSlot(System.currentTimeMillis())
        if (windowStart < earliest) windowStart = earliest
        if (focusTime < earliest) focusTime = System.currentTimeMillis()
        invalidate()
    }

    fun page(direction: Int) = moveRow(direction * visibleRows().coerceAtLeast(1))

    fun jumpToRow(row: Int) {
        if (channels.isEmpty()) return
        focusRow = row.coerceIn(0, channels.size - 1)
        keepFocusVisible(centre = true)
        invalidate()
        notifyFocus()
    }

    fun focusedProgramme(): Programme? = programmeAt(focusRow, focusTime)

    // ---------------------------------------------------------------- remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (channels.isNotEmpty()) listener?.onGuideSelect(focusRow, focusedProgramme())
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        val target = (focusRow + delta).coerceIn(0, channels.size - 1)
        if (target == focusRow) return
        focusRow = target
        keepFocusVisible(centre = false)
        invalidate()
        notifyFocus()
    }

    private fun moveTime(direction: Int) {
        if (channels.isEmpty()) return
        val earliest = floorSlot(System.currentTimeMillis())
        val latest = System.currentTimeMillis() + MAX_AHEAD_MS
        val current = focusedProgramme()
        if (direction > 0) {
            val next = current?.stop ?: (floorSlot(focusTime) + SLOT_MS)
            if (next >= latest) return
            focusTime = next
        } else {
            val start = current?.start ?: floorSlot(focusTime)
            if (start <= earliest) return
            val before = programmeAt(focusRow, start - 1)
            focusTime = maxOf(before?.start ?: floorSlot(start - 1), earliest)
        }
        keepTimeVisible()
        invalidate()
        notifyFocus()
    }

    private fun notifyFocus() {
        if (channels.isEmpty()) return
        listener?.onGuideFocus(focusRow, focusedProgramme(), floorSlot(focusTime))
    }

    // ---------------------------------------------------------------- scrolling

    private fun visibleRows(): Int = ((height - headerHeight) / rowHeight).toInt()

    private fun keepFocusVisible(centre: Boolean) {
        val rows = visibleRows()
        if (rows <= 0) return
        if (centre) {
            firstRow = (focusRow - rows / 2)
        } else if (focusRow < firstRow) {
            firstRow = focusRow
        } else if (focusRow >= firstRow + rows) {
            firstRow = focusRow - rows + 1
        }
        firstRow = firstRow.coerceIn(0, (channels.size - rows).coerceAtLeast(0))
    }

    private fun keepTimeVisible() {
        val cellStart = maxOf(focusedProgramme()?.start ?: floorSlot(focusTime), floorSlot(System.currentTimeMillis()))
        val windowEnd = windowStart + WINDOW_MS
        if (cellStart < windowStart) {
            windowStart = floorSlot(cellStart)
        } else if (cellStart >= windowEnd - SLOT_MS / 2) {
            windowStart = floorSlot(cellStart) - SLOT_MS
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keepFocusVisible(centre = true)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        val gridLeft = channelColumn
        val gridWidth = width - gridLeft
        val pxPerMs = gridWidth / WINDOW_MS.toFloat()
        val windowEnd = windowStart + WINDOW_MS
        val now = System.currentTimeMillis()

        // Time ruler
        var t = windowStart
        while (t < windowEnd) {
            val x = gridLeft + (t - windowStart) * pxPerMs
            canvas.drawText(timeFormat.format(Date(t)), x + dp(6f), headerHeight - dp(9f), smallPaint)
            fill.color = COLOR_RULE
            canvas.drawRect(x, headerHeight - dp(4f), x + dp(1f), headerHeight, fill)
            t += SLOT_MS
        }

        val rows = visibleRows() + 1
        for (i in 0 until rows) {
            val row = firstRow + i
            if (row >= channels.size) break
            val top = headerHeight + i * rowHeight
            val bottom = top + rowHeight - gap
            val channel = channels[row]
            val rowFocused = row == focusRow

            // Channel name
            rect.set(0f, top, gridLeft - gap, bottom)
            fill.color = if (rowFocused) COLOR_CHANNEL_FOCUS else COLOR_CHANNEL
            canvas.drawRoundRect(rect, radius, radius, fill)
            val numberText = (row + 1).toString()
            canvas.drawText(numberText, pad, centreY(top, bottom, numberPaint), numberPaint)
            val nameLeft = pad + dp(40f)
            titlePaint.color = if (channel.id == playingId) COLOR_ACCENT else Color.WHITE
            val name = TextUtils.ellipsize(channel.name, titlePaint, gridLeft - gap - nameLeft - pad, TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, nameLeft, centreY(top, bottom, titlePaint), titlePaint)
            titlePaint.color = Color.WHITE

            // Empty background for the row (shows through where there are no listings)
            rect.set(gridLeft, top, width.toFloat(), bottom)
            fill.color = COLOR_EMPTY
            canvas.drawRoundRect(rect, radius, radius, fill)

            val programmes = guide.programmes(channel).filter { it.stop > windowStart && it.start < windowEnd }
            if (programmes.isEmpty()) {
                drawCellText(canvas, context.getString(R.string.guide_no_info), gridLeft, width.toFloat(), top, bottom, smallPaint)
            }
            for (p in programmes) {
                val x1 = gridLeft + (maxOf(p.start, windowStart) - windowStart) * pxPerMs
                val x2 = gridLeft + (minOf(p.stop, windowEnd) - windowStart) * pxPerMs
                val focused = rowFocused && p.start <= focusTime && p.stop > focusTime
                val onNow = p.start <= now && p.stop > now
                rect.set(x1, top, x2 - gap, bottom)
                fill.color = when {
                    focused -> COLOR_FOCUS
                    onNow -> COLOR_NOW
                    else -> COLOR_CELL
                }
                canvas.drawRoundRect(rect, radius, radius, fill)
                drawCellText(canvas, p.title, x1, x2 - gap, top, bottom, titlePaint)
            }

            // Highlight a gap with no listings
            if (rowFocused && programmeAt(row, focusTime) == null) {
                val slot = floorSlot(focusTime)
                val x1 = gridLeft + (maxOf(slot, windowStart) - windowStart) * pxPerMs
                val x2 = gridLeft + (minOf(slot + SLOT_MS, windowEnd) - windowStart) * pxPerMs
                rect.set(x1, top, x2 - gap, bottom)
                fill.color = COLOR_FOCUS
                canvas.drawRoundRect(rect, radius, radius, fill)
                drawCellText(canvas, context.getString(R.string.guide_no_info), x1, x2 - gap, top, bottom, titlePaint)
            }
        }

        // "Now" line
        if (now in windowStart until windowEnd) {
            val x = gridLeft + (now - windowStart) * pxPerMs
            fill.color = COLOR_NOW_LINE
            canvas.drawRect(x - dp(1f), headerHeight - dp(6f), x + dp(1f), height.toFloat(), fill)
        }
    }

    private fun drawCellText(canvas: Canvas, text: String, left: Float, right: Float, top: Float, bottom: Float, paint: TextPaint) {
        val available = right - left - pad * 2
        if (available < dp(12f)) return
        val shown = TextUtils.ellipsize(text, paint, available, TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, left + pad, centreY(top, bottom, paint), paint)
    }

    private fun centreY(top: Float, bottom: Float, paint: Paint): Float =
        (top + bottom) / 2 - (paint.descent() + paint.ascent()) / 2

    // ---------------------------------------------------------------- helpers

    private fun programmeAt(row: Int, time: Long): Programme? {
        val channel = channels.getOrNull(row) ?: return null
        return guide.programmes(channel).firstOrNull { it.start <= time && it.stop > time }
    }

    private fun floorSlot(time: Long): Long = time - ((time % SLOT_MS) + SLOT_MS) % SLOT_MS

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val SLOT_MS = 30L * 60 * 1000
        private const val WINDOW_MS = 2L * 60 * 60 * 1000 // 2 hours across the screen
        private const val MAX_AHEAD_MS = 34L * 60 * 60 * 1000

        private val COLOR_DIM = Color.parseColor("#B4BCC8")
        private val COLOR_RULE = Color.parseColor("#55B4BCC8")
        private val COLOR_CHANNEL = Color.parseColor("#1E2530")
        private val COLOR_CHANNEL_FOCUS = Color.parseColor("#2C3646")
        private val COLOR_EMPTY = Color.parseColor("#161B22")
        private val COLOR_CELL = Color.parseColor("#232B37")
        private val COLOR_NOW = Color.parseColor("#2C3646")
        private val COLOR_FOCUS = Color.parseColor("#2F7BF0")
        private val COLOR_ACCENT = Color.parseColor("#4C9BFF")
        private val COLOR_NOW_LINE = Color.parseColor("#FFE0484D")
    }
}
