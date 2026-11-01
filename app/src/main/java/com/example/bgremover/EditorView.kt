package com.example.bgremover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { ERASE, RESTORE, LASSO, WAND, PAN }

    var mode = Mode.ERASE
    var brushSize = 40f          // dalam px layar
    var tolerance = 25           // 0..100
    var lassoKeepInside = false

    private var original: Bitmap? = null
    private var work: Bitmap? = null
    private var workCanvas: Canvas? = null
    private val undoStack = ArrayDeque<Bitmap>()

    private val matrix = Matrix()
    private val inv = Matrix()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkerPaint = Paint()
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val restorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val lassoLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 3f
    }

    private val lassoScreen = Path()
    private val lassoImage = Path()

    private var lastX = 0f
    private var lastY = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var drawing = false
    private var ignore = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = scaleNow()
                val target = (cur * d.scaleFactor).coerceIn(0.05f, 60f)
                val f = target / cur
                matrix.postScale(f, f, d.focusX, d.focusY)
                invalidate()
                return true
            }
        })

    init {
        val s = 16
        val tile = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.rgb(200, 200, 200) }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), s * 2f, s * 2f, p)
        checkerPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ---------- API publik ----------

    fun setImage(bmp: Bitmap) {
        val o = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val w = bmp.copy(Bitmap.Config.ARGB_8888, true)
        original = o
        work = w
        workCanvas = Canvas(w)
        restorePaint.shader = BitmapShader(o, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        undoStack.clear()
        fit()
    }

    fun result(): Bitmap? = work

    fun undo() {
        val b = undoStack.removeLastOrNull() ?: return
        workCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)
        workCanvas?.drawBitmap(b, 0f, 0f, null)
        b.recycle()
        invalidate()
    }

    fun resetImage() {
        val o = original ?: return
        pushUndo()
        workCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)
        workCanvas?.drawBitmap(o, 0f, 0f, null)
        invalidate()
    }

    // ---------- util ----------

    private fun scaleNow(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        matrix.invert(inv)
        val pts = floatArrayOf(x, y)
        inv.mapPoints(pts)
        return pts
    }

    private fun fit() {
        val b = work ?: return
        if (width == 0 || height == 0) return
        matrix.reset()
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.postScale(s, s)
        matrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun pushUndo() {
        val w = work ?: return
        undoStack.addLast(w.copy(Bitmap.Config.ARGB_8888, false))
        if (undoStack.size > 6) undoStack.removeFirst().recycle()
    }

    private fun brushPaint(): Paint {
        val p = if (mode == Mode.ERASE) erasePaint else restorePaint
        p.strokeWidth = brushSize / scaleNow()
        return p
    }

    // ---------- gambar ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = work ?: return
        canvas.save()
        canvas.concat(matrix)
        canvas.drawRect(0f, 0f, w.width.toFloat(), w.height.toFloat(), checkerPaint)
        canvas.restore()
        canvas.drawBitmap(w, matrix, bmpPaint)
        if (mode == Mode.LASSO && drawing) canvas.drawPath(lassoScreen, lassoLine)
        if (touchX >= 0 && (mode == Mode.ERASE || mode == Mode.RESTORE)) {
            canvas.drawCircle(touchX, touchY, brushSize / 2f, cursorPaint)
        }
    }

    // ---------- sentuhan ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (work == null) return true
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignore = false
                startAction(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelAction()
                ignore = true
                lastFx = (e.getX(0) + e.getX(1)) / 2f
                lastFy = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val fx = (e.getX(0) + e.getX(1)) / 2f
                    val fy = (e.getY(0) + e.getY(1)) / 2f
                    matrix.postTranslate(fx - lastFx, fy - lastFy)
                    lastFx = fx
                    lastFy = fy
                    invalidate()
                } else if (!ignore) {
                    moveAction(e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!ignore) endAction(e.x, e.y)
                touchX = -1f
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAction()
                touchX = -1f
                invalidate()
            }
        }
        return true
    }

    private fun startAction(x: Float, y: Float) {
        touchX = x
        touchY = y
        val p = toImage(x, y)
        when (mode) {
            Mode.ERASE, Mode.RESTORE -> {
                pushUndo()
                drawing = true
                workCanvas?.drawPoint(p[0], p[1], brushPaint())
                lastX = p[0]
                lastY = p[1]
            }
            Mode.LASSO -> {
                drawing = true
                lassoScreen.reset()
                lassoImage.reset()
                lassoScreen.moveTo(x, y)
                lassoImage.moveTo(p[0], p[1])
            }
            Mode.PAN -> {
                drawing = true
                lastX = x
                lastY = y
            }
            Mode.WAND -> {}
        }
        invalidate()
    }

    private fun moveAction(x: Float, y: Float) {
        touchX = x
        touchY = y
        when (mode) {
            Mode.ERASE, Mode.RESTORE -> {
                if (!drawing) return
                val p = toImage(x, y)
                workCanvas?.drawLine(lastX, lastY, p[0], p[1], brushPaint())
                lastX = p[0]
                lastY = p[1]
            }
            Mode.LASSO -> {
                if (!drawing) return
                val p = toImage(x, y)
                lassoScreen.lineTo(x, y)
                lassoImage.lineTo(p[0], p[1])
            }
            Mode.PAN -> {
                matrix.postTranslate(x - lastX, y - lastY)
                lastX = x
                lastY = y
            }
            Mode.WAND -> {}
        }
        invalidate()
    }

    private fun endAction(x: Float, y: Float) {
        when (mode) {
            Mode.LASSO -> {
                if (drawing) {
                    lassoImage.close()
                    pushUndo()
                    lassoImage.fillType = if (lassoKeepInside) Path.FillType.INVERSE_EVEN_ODD
                    else Path.FillType.EVEN_ODD
                    workCanvas?.drawPath(lassoImage, lassoFill)
                    lassoScreen.reset()
                    lassoImage.reset()
                }
            }
            Mode.WAND -> {
                val p = toImage(x, y)
                floodFill(p[0].toInt(), p[1].toInt())
            }
            else -> {}
        }
        drawing = false
        invalidate()
    }

    private fun cancelAction() {
        if (drawing) {
            when (mode) {
                Mode.ERASE, Mode.RESTORE -> undo()
                Mode.LASSO -> {
                    lassoScreen.reset()
                    lassoImage.reset()
                }
                else -> {}
            }
        }
        drawing = false
        invalidate()
    }

    // ---------- tongkat ajaib ----------

    private fun floodFill(sx: Int, sy: Int) {
        val bmp = work ?: return
        val w = bmp.width
        val h = bmp.height
        if (sx !in 0 until w || sy !in 0 until h) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val seed = px[sy * w + sx]
        if (Color.alpha(seed) == 0) return
        pushUndo()

        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        val t = (tolerance * 2.55f).toInt()

        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = sy * w + sx
        visited[sy * w + sx] = true

        while (sp > 0) {
            val i = stack[--sp]
            px[i] = 0
            val x = i % w
            val y = i / w
            for (k in 0 until 4) {
                val nx = when (k) { 0 -> x - 1; 1 -> x + 1; else -> x }
                val ny = when (k) { 2 -> y - 1; 3 -> y + 1; else -> y }
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (visited[j]) continue
                val c = px[j]
                if (Color.alpha(c) != 0 &&
                    abs(Color.red(c) - sr) <= t &&
                    abs(Color.green(c) - sg) <= t &&
                    abs(Color.blue(c) - sb) <= t
                ) {
                    visited[j] = true
                    stack[sp++] = j
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
