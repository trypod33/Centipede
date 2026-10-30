package com.trypod33.centipede

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val STATE_READY = 0
        const val STATE_PLAYING = 1
        const val STATE_DYING = 2
        const val STATE_OVER = 3
    }

    private val prefs = context.getSharedPreferences("centipede", Context.MODE_PRIVATE)
    private var highScore = prefs.getInt("high", 0)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastNs = 0L
    private var time = 0f

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 30f
    }

    private val COLS = 28
    private val ROWS = 26
    private val ZONE_ROWS = 6
    private var cell = 20f
    private var fieldLeft = 0f
    private var fieldTop = 90f
    private var screenW = 0f
    private var screenH = 0f

    private var state = STATE_READY
    private var score = 0
    private var lives = 3
    private var wave = 1

    class Mushroom(val col: Int, val row: Int, var hp: Int)
    class Seg(var col: Int, var row: Int, var dir: Int, var isHead: Boolean)

    private val mush = HashMap<Long, Mushroom>()
    private val chains = ArrayList<MutableList<Seg>>()
    private val bullets = ArrayList<FloatArray>()
    private var spider: FloatArray? = null
    private var spiderTimer = 5f
    private var flea: FloatArray? = null
    private var fleaHp = 2
    private var fleaCheck = 0f

    private var px = 0f
    private var py = 0f
    private var tx = 0f
    private var ty = 0f
    private var touching = false
    private var fireT = 0f
    private var invuln = 0f
    private var stepT = 0f
    private var deathT = 0f
    private val stars = ArrayList<FloatArray>()

    init {
        holder.addCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        screenW = w.toFloat()
        screenH = h.toFloat()
        cell = min(w / COLS.toFloat(), (h - 100f) / ROWS)
        fieldLeft = (screenW - cell * COLS) / 2f
        fieldTop = (screenH - cell * ROWS) / 2f + 40f
        stars.clear()
        repeat(70) {
            stars.add(floatArrayOf(Random.nextFloat() * screenW, Random.nextFloat() * screenH, Random.nextFloat()))
        }
        reset()
    }

    private fun reset() {
        score = 0
        lives = 3
        wave = 1
        mush.clear()
        chains.clear()
        bullets.clear()
        spider = null
        flea = null
        seedField()
        spawnChain(12, 2)
        px = fieldLeft + cell * COLS / 2
        py = zoneBottomY()
        tx = px
        ty = py
        invuln = 0f
    }

    private fun seedField() {
        for (r in 2 until ROWS - 2) {
            for (c in 0 until COLS) {
                if (Random.nextFloat() < 0.085f) mush[key(c, r)] = Mushroom(c, r, 4)
            }
        }
    }

    private fun spawnChain(len: Int, startRow: Int) {
        val dir = if (Random.nextBoolean()) 1 else -1
        val startCol = if (dir == 1) 3 else COLS - 4
        val chain = ArrayList<Seg>()
        for (i in 0 until len) {
            val c = (startCol - i * dir).coerceIn(0, COLS - 1)
            chain.add(Seg(c, startRow, dir, i == 0))
        }
        chains.add(chain)
    }

    private fun spawnWave(w: Int) {
        chains.clear()
        spawnChain(12, 2)
        val solos = min(w - 1, 5)
        repeat(solos) {
            val chain = ArrayList<Seg>()
            chain.add(Seg(Random.nextInt(COLS), Random.nextInt(4) + 1, if (Random.nextBoolean()) 1 else -1, true))
            chains.add(chain)
        }
    }

    private fun key(c: Int, r: Int) = r.toLong() * 1000 + c
    private fun cellX(c: Int) = fieldLeft + cell * (c + 0.5f)
    private fun cellY(r: Int) = fieldTop + cell * (r + 0.5f)
    private fun zoneTopY() = fieldTop + cell * (ROWS - ZONE_ROWS)
    private fun zoneBottomY() = fieldTop + cell * ROWS - cell * 0.6f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (state == STATE_READY || state == STATE_OVER) {
                    reset()
                    state = STATE_PLAYING
                }
                touching = true
                tx = e.x
                ty = e.y - cell * 2f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }

    fun pause() {
        running = false
        thread?.interrupt()
    }

    fun resume() {
        if (!running && holder.surface.isValid) {
            running = true
            thread = Thread(this).also { it.start() }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { resume() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) { pause() }

    override fun run() {
        while (running) {
            val canvas = try { holder.lockCanvas() } catch (t: Throwable) { null } ?: continue
            try {
                val now = System.nanoTime()
                if (lastNs == 0L) lastNs = now
                var dt = (now - lastNs) / 1e9f
                lastNs = now
                if (dt > 0.05f) dt = 0.05f
                update(dt)
                render(canvas)
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (t: Throwable) {}
            }
        }
    }

    private fun update(dt: Float) {
        time += dt
        if (state == STATE_READY || state == STATE_OVER) return
        if (invuln > 0f) invuln -= dt

        // player movement
        val sp = 16f * cell * dt
        val dx = tx - px
        px += sign(dx) * min(sp, abs(dx))
        val dy = ty - py
        py += sign(dy) * min(sp, abs(dy))
        px = px.coerceIn(fieldLeft + cell * 0.5f, fieldLeft + cell * (COLS - 0.5f))
        py = py.coerceIn(zoneTopY() + cell * 0.4f, zoneBottomY())

        if (state == STATE_DYING) {
            deathT -= dt
            if (deathT <= 0f) afterDeath()
            return
        }

        // firing
        fireT += dt
        if (touching && fireT >= 0.12f) {
            fireT = 0f
            bullets.add(floatArrayOf(px, py - cell * 0.7f))
        }

        updateBullets(dt)
        stepCentipede(dt)
        updateSpider(dt)
        updateFlea(dt)
        checkPlayerHit()
    }

    private fun updateBullets(dt: Float) {
        val it = bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b[1] -= cell * 12f * dt
            if (b[1] < fieldTop) { it.remove(); continue }

            // mushroom hit
            val c = ((b[0] - fieldLeft) / cell).toInt()
            val r = ((b[1] - fieldTop) / cell).toInt()
            if (c in 0 until COLS && r in 0 until ROWS) {
                val m = mush[key(c, r)]
                if (m != null) {
                    m.hp--
                    if (m.hp <= 0) { mush.remove(key(c, r)); score += 1 }
                    it.remove()
                    continue
                }
            }

            // centipede hit
            var hit = false
            for (ci in chains.indices) {
                val chain = chains[ci]
                for (si in chain.indices) {
                    val s = chain[si]
                    if (abs(b[0] - cellX(s.col)) < cell * 0.45f && abs(b[1] - cellY(s.row)) < cell * 0.45f) {
                        score += if (s.isHead) 100 else 10
                        if (!s.isHead && !mush.containsKey(key(s.col, s.row))) {
                            mush[key(s.col, s.row)] = Mushroom(s.col, s.row, 4)
                        }
                        val idx = si
                        chain.removeAt(idx)
                        if (idx == 0) {
                            if (chain.isNotEmpty()) chain[0].isHead = true
                        } else if (chain.isNotEmpty()) {
                            val rear = chain.subList(idx, chain.size).toMutableList()
                            chain.subList(idx, chain.size).clear()
                            rear[0].isHead = true
                            chains.add(rear)
                        }
                        hit = true
                        break
                    }
                }
                if (hit) break
            }
            if (hit) {
                it.remove()
                chains.removeAll { it.isEmpty() }
                if (chains.isEmpty()) {
                    wave++
                    spawnWave(wave)
                }
                continue
            }

            // spider / flea hit
            val sp2 = spider
            if (sp2 != null && abs(b[0] - sp2[0]) < cell * 0.5f && abs(b[1] - sp2[1]) < cell * 0.5f) {
                score += 300
                spider = null
                it.remove()
                continue
            }
            val fl = flea
            if (fl != null && abs(b[0] - fl[0]) < cell * 0.4f && abs(b[1] - fl[1]) < cell * 0.4f) {
                fleaHp--
                if (fleaHp <= 0) {
                    score += 200
                    flea = null
                }
                it.remove()
            }
        }
    }

    private fun stepCentipede(dt: Float) {
        val interval = 0.24f / (1f + (wave - 1) * 0.10f)
        stepT += dt
        if (stepT < interval) return
        stepT -= interval
        for (chain in chains) {
            for (s in chain) {
                var nc = s.col + s.dir
                var nr = s.row
                if (nc < 0 || nc >= COLS || mush.containsKey(key(nc, nr))) {
                    s.dir = -s.dir
                    nc = s.col
                    nr += 1
                }
                if (nr >= ROWS - 1) {
                    nr = 0
                    s.isHead = true
                }
                s.col = nc
                s.row = nr
            }
        }
    }

    private fun updateSpider(dt: Float) {
        spiderTimer -= dt
        val sp2 = spider
        if (sp2 == null) {
            if (spiderTimer <= 0f) {
                spiderTimer = 4f + Random.nextFloat() * 5f
                val fromLeft = Random.nextBoolean()
                val x = if (fromLeft) fieldLeft - cell else fieldLeft + cell * COLS
                val y = zoneTopY() + Random.nextFloat() * (zoneBottomY() - zoneTopY())
                val dx = (if (fromLeft) 1 else -1) * cell * (4f + Random.nextFloat() * 2f)
                val dy = (if (Random.nextBoolean()) 1 else -1) * cell * 3f
                spider = floatArrayOf(x, y, dx, dy)
            }
            return
        }
        sp2[0] += sp2[2] * dt
        sp2[1] += sp2[3] * dt
        if (sp2[1] < zoneTopY() + cell * 0.3f) { sp2[1] = zoneTopY() + cell * 0.3f; sp2[3] = abs(sp2[3]) }
        if (sp2[1] > zoneBottomY() + cell * 0.3f) { sp2[1] = zoneBottomY() + cell * 0.3f; sp2[3] = -abs(sp2[3]) }
        if (sp2[0] < fieldLeft - cell * 1.5f || sp2[0] > fieldLeft + cell * (COLS + 1.5f)) {
            spider = null
            return
        }
        // eat mushrooms it passes
        val c = ((sp2[0] - fieldLeft) / cell).toInt()
        val r = ((sp2[1] - fieldTop) / cell).toInt()
        if (c in 0 until COLS && r in 0 until ROWS && Random.nextFloat() < dt * 3f) {
            mush.remove(key(c, r))
        }
    }

    private fun updateFlea(dt: Float) {
        val fl = flea
        if (fl != null) {
            val oldRow = ((fl[1] - fieldTop) / cell).toInt()
            fl[1] += cell * 7f * dt
            val newRow = ((fl[1] - fieldTop) / cell).toInt()
            if (newRow > oldRow && newRow in 1 until ROWS - 1) {
                val c = ((fl[0] - fieldLeft) / cell).toInt()
                if (c in 0 until COLS && Random.nextFloat() < 0.6f && !mush.containsKey(key(c, newRow))) {
                    mush[key(c, newRow)] = Mushroom(c, newRow, 4)
                }
            }
            if (fl[1] > zoneBottomY()) flea = null
            return
        }
        fleaCheck += dt
        if (fleaCheck >= 1f) {
            fleaCheck = 0f
            var count = 0
            for (r in (ROWS - ZONE_ROWS) until ROWS) for (c in 0 until COLS) if (mush.containsKey(key(c, r))) count++
            if (count < 5 && Random.nextFloat() < 0.5f) {
                val c = Random.nextInt(COLS)
                flea = floatArrayOf(cellX(c), fieldTop)
                fleaHp = 2
            }
        }
    }

    private fun checkPlayerHit() {
        if (invuln > 0f) return
        var hit = false
        outer@ for (chain in chains) {
            for (s in chain) {
                if (abs(px - cellX(s.col)) < cell * 0.5f && abs(py - cellY(s.row)) < cell * 0.5f) { hit = true; break@outer }
            }
        }
        val sp2 = spider
        if (!hit && sp2 != null && abs(px - sp2[0]) < cell * 0.6f && abs(py - sp2[1]) < cell * 0.6f) hit = true
        val fl = flea
        if (!hit && fl != null && abs(px - fl[0]) < cell * 0.5f && abs(py - fl[1]) < cell * 0.5f) hit = true
        if (hit) {
            state = STATE_DYING
            deathT = 1.1f
            bullets.clear()
            touching = false
        }
    }

    private fun afterDeath() {
        lives--
        if (lives <= 0) {
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt("high", highScore).apply()
            }
            state = STATE_OVER
        } else {
            state = STATE_PLAYING
            px = fieldLeft + cell * COLS / 2
            py = zoneBottomY()
            tx = px
            ty = py
            invuln = 2f
            if (chains.isEmpty()) spawnChain(12, 2)
        }
    }

    private fun render(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // stars
        for (s in stars) {
            pFill.alpha = (50 + 140 * abs(kotlin.math.sin(time * 1.5f + s[2] * 6.28f))).toInt()
            pFill.color = Color.WHITE
            canvas.drawCircle(s[0], s[1], 1.5f, pFill)
        }
        pFill.alpha = 255

        // mushrooms
        for (m in mush.values) {
            val cx = cellX(m.col)
            val cy = cellY(m.row)
            val r = cell * (0.16f + m.hp * 0.055f)
            pFill.color = Color.rgb(255, 120, 0)
            canvas.drawCircle(cx, cy - cell * 0.10f, r, pFill)
            pFill.color = Color.rgb(230, 200, 160)
            canvas.drawRect(cx - cell * 0.11f, cy - cell * 0.05f, cx + cell * 0.11f, cy + cell * 0.34f, pFill)
        }

        // centipede
        for (chain in chains) {
            for (s in chain) {
                val x = cellX(s.col)
                val y = cellY(s.row)
                pFill.color = if (s.isHead) Color.rgb(190, 255, 80) else Color.rgb(70, 200, 70)
                canvas.drawCircle(x, y, cell * if (s.isHead) 0.44f else 0.38f, pFill)
                pStroke.color = Color.rgb(30, 110, 30)
                canvas.drawCircle(x, y, cell * if (s.isHead) 0.44f else 0.38f, pStroke)
                if (s.isHead) {
                    pFill.color = Color.BLACK
                    val eo = cell * 0.16f
                    canvas.drawCircle(x + s.dir * eo, y - eo, cell * 0.07f, pFill)
                    canvas.drawCircle(x + s.dir * eo, y + eo, cell * 0.07f, pFill)
                }
            }
        }

        // spider
        spider?.let { sp2 ->
            val x = sp2[0]; val y = sp2[1]
            pStroke.color = Color.rgb(255, 60, 200)
            val l = cell * 0.55f
            for (i in 0 until 4) {
                val a = (-0.6f + i * 0.4f)
                canvas.drawLine(x - cell * 0.2f, y, x - l, y + a * cell, pStroke)
                canvas.drawLine(x + cell * 0.2f, y, x + l, y + a * cell, pStroke)
            }
            pFill.color = Color.rgb(255, 60, 200)
            canvas.drawCircle(x, y, cell * 0.34f, pFill)
        }

        // flea
        flea?.let {
            pFill.color = Color.rgb(190, 80, 255)
            canvas.drawCircle(it[0], it[1], cell * 0.30f, pFill)
        }

        // bullets
        pFill.color = Color.WHITE
        for (b in bullets) canvas.drawRect(b[0] - 2.5f, b[1] - cell * 0.4f, b[0] + 2.5f, b[1] + cell * 0.4f, pFill)

        // player
        if (state == STATE_PLAYING || state == STATE_READY) {
            val blink = invuln > 0f && ((invuln * 8f).toInt() % 2 == 0)
            if (!blink) {
                val path = Path()
                path.moveTo(px, py - cell * 0.75f)
                path.lineTo(px - cell * 0.5f, py + cell * 0.55f)
                path.lineTo(px, py + cell * 0.25f)
                path.lineTo(px + cell * 0.5f, py + cell * 0.55f)
                path.close()
                pFill.color = Color.CYAN
                canvas.drawPath(path, pFill)
            }
        }

        drawHud(canvas)
        drawOverlays(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        pText.color = Color.WHITE
        canvas.drawText("SCORE %05d".format(score), 16f, 46f, pText)
        canvas.drawText("HI %05d".format(highScore), screenW / 2f - 110f, 46f, pText)
        pText.color = Color.rgb(255, 200, 60)
        canvas.drawText("WAVE $wave", screenW - 210f, 46f, pText)
        // lives
        for (i in 0 until lives) {
            val x = 30f + i * 44f
            val y = screenH - 26f
            val path = Path()
            path.moveTo(x, y - 14f)
            path.lineTo(x - 11f, y + 10f)
            path.lineTo(x + 11f, y + 10f)
            path.close()
            pFill.color = Color.CYAN
            canvas.drawPath(path, pFill)
        }
    }

    private fun drawOverlays(canvas: Canvas) {
        val cx = screenW / 2f
        val cy = screenH / 2f
        pText.textAlign = Paint.Align.CENTER
        when (state) {
            STATE_READY -> {
                pText.color = Color.rgb(190, 255, 80)
                pText.textSize = 64f
                canvas.drawText("CENTIPEDE", cx, cy - 80f, pText)
                pText.color = Color.WHITE
                pText.textSize = 40f
                canvas.drawText("TAP TO START", cx, cy + 10f, pText)
                pSmall.textAlign = Paint.Align.CENTER
                canvas.drawText("drag to move  |  hold to fire", cx, cy + 60f, pSmall)
            }
            STATE_OVER -> {
                pText.color = Color.rgb(255, 80, 80)
                pText.textSize = 58f
                canvas.drawText("GAME OVER", cx, cy - 60f, pText)
                pText.color = Color.WHITE
                pText.textSize = 42f
                canvas.drawText("SCORE %05d".format(score), cx, cy, pText)
                pText.color = if (score >= highScore && score > 0) Color.rgb(255, 220, 60) else Color.LTGRAY
                canvas.drawText("HI %05d".format(highScore), cx, cy + 48f, pText)
                pText.color = Color.WHITE
                canvas.drawText("TAP TO PLAY AGAIN", cx, cy + 120f, pText)
            }
        }
        pText.textSize = 40f
        pText.textAlign = Paint.Align.LEFT
        pSmall.textAlign = Paint.Align.LEFT
    }
}
