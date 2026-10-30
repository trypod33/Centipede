package com.trypod33.centipede

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
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
    private val pFloat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 220, 80)
        textSize = 26f
        isFakeBoldText = true
    }
    private val pSprite = Paint().apply { isFilterBitmap = false }

    // ---- sprites ----
    private class Sprite(val bmp: Bitmap, glow: Int) {
        private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(glow, PorterDuff.Mode.SRC_IN)
            alpha = 70
        }
        fun draw(canvas: Canvas, cx: Float, cy: Float, w: Float, p: Paint, rot: Float = 0f, flipH: Boolean = false) {
            val h = w * bmp.height.toFloat() / bmp.width
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val grect = RectF(cx - w * 0.64f, cy - h * 0.64f, cx + w * 0.64f, cy + h * 0.64f)
            if (flipH) {
                canvas.save()
                canvas.scale(-1f, 1f, cx, cy)
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
                canvas.restore()
                return
            }
            if (rot != 0f) {
                canvas.save()
                canvas.rotate(rot, cx, cy)
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, null, grect, pGlow)
                canvas.drawBitmap(bmp, null, rect, p)
            }
        }
    }
    private val sprMush = arrayOfNulls<Sprite>(5) // 1..4 damage states
    private var sprBody: Sprite? = null
    private var sprHead: Sprite? = null
    private var sprSpider = arrayOfNulls<Sprite>(2)
    private var sprFlea = arrayOfNulls<Sprite>(2)
    private var sprShip: Sprite? = null
    private var sprMushP = arrayOfNulls<Sprite>(5)
    private var sprScorp = arrayOfNulls<Sprite>(2)

    // ---- sound ----
    private val snd: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sIds = HashMap<String, Int>()
    private fun play(name: String, vol: Float = 0.55f) {
        val id = sIds[name] ?: return
        snd.play(id, vol, vol, 1, 0, 1f)
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

    class Mushroom(val col: Int, val row: Int, var hp: Int, var poisoned: Boolean = false)
    class Seg(var col: Int, var row: Int, var dir: Int, var isHead: Boolean, var poisoned: Boolean = false)

    private val mush = HashMap<Long, Mushroom>()
    private val chains = ArrayList<MutableList<Seg>>()
    private val bullets = ArrayList<FloatArray>()
    private var spider: FloatArray? = null
    private var spiderTimer = 5f
    private var flea: FloatArray? = null
    private var fleaHp = 2
    private var fleaCheck = 0f
    private var scorp: FloatArray? = null
    private var scorpTimer = 9f

    private var px = 0f
    private var py = 0f
    private var tx = 0f
    private var ty = 0f
    private var touching = false
    private var fireT = 0f
    private var invuln = 0f
    private var stepT = 0f
    private var deathT = 0f
    private var shakeT = 0f
    private val stars = ArrayList<FloatArray>()
    private var pNeb1: Paint? = null
    private var pNeb2: Paint? = null
    private val parts = ArrayList<P>()
    private val floats = ArrayList<FT>()

    class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val col: Int)
    class FT(var x: Float, var y: Float, val text: String, var life: Float = 0.8f)

    init {
        holder.addCallback(this)
        fun spr(name: String, glow: Int): Sprite =
            Sprite(BitmapFactory.decodeResource(resources, resources.getIdentifier("spr_$name", "drawable", context.packageName)), glow)
        for (i in 1..4) sprMush[i] = spr("mushroom_$i", Color.rgb(120, 255, 120))
        sprBody = spr("c_body", Color.rgb(110, 255, 110))
        sprHead = spr("c_head", Color.rgb(150, 255, 90))
        sprSpider[0] = spr("spider_0", Color.rgb(255, 80, 140)); sprSpider[1] = spr("spider_1", Color.rgb(255, 80, 140))
        sprFlea[0] = spr("flea_0", Color.rgb(200, 120, 255)); sprFlea[1] = spr("flea_1", Color.rgb(200, 120, 255))
        sprShip = spr("ship", Color.rgb(90, 210, 255))
        for (i in 1..4) sprMushP[i] = spr("mushp_$i", Color.rgb(210, 120, 255))
        sprScorp[0] = spr("scorp_0", Color.rgb(255, 220, 60)); sprScorp[1] = spr("scorp_1", Color.rgb(255, 220, 60))
        for (n in listOf("shoot", "mush", "mushbrk", "kill", "head", "spider", "flea", "die", "wave", "poison", "scorp")) {
            sIds[n] = snd.load(context, resources.getIdentifier(n, "raw", context.packageName), 1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        screenW = w.toFloat()
        screenH = h.toFloat()
        cell = min(w / COLS.toFloat(), (h - 100f) / ROWS)
        fieldLeft = (screenW - cell * COLS) / 2f
        fieldTop = (screenH - cell * ROWS) / 2f + 40f
        pNeb1 = Paint().apply { shader = RadialGradient(screenW * 0.28f, screenH * 0.30f, screenH * 0.55f, Color.argb(34, 90, 0, 140), Color.TRANSPARENT, Shader.TileMode.CLAMP) }
        pNeb2 = Paint().apply { shader = RadialGradient(screenW * 0.75f, screenH * 0.62f, screenH * 0.50f, Color.argb(28, 0, 90, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP) }
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
        scorp = null
        parts.clear()
        floats.clear()
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

    private fun burst(x: Float, y: Float, col: Int, n: Int, speed: Float = 4f) {
        repeat(n) {
            val a = Random.nextFloat() * 6.283f
            val v = speed * cell * (0.4f + Random.nextFloat() * 0.8f)
            parts.add(P(x, y, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v, 0.35f + Random.nextFloat() * 0.35f, col))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (state == STATE_READY || state == STATE_OVER) {
                    reset()
                    state = STATE_PLAYING
                    play("wave", 0.5f)
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
        if (shakeT > 0f) shakeT -= dt
        // particles / floating text run in every state
        val pit = parts.iterator()
        while (pit.hasNext()) {
            val q = pit.next()
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.vx *= (1f - 2.5f * dt)
            q.vy *= (1f - 2.5f * dt)
            q.life -= dt
            if (q.life <= 0f) pit.remove()
        }
        val fit = floats.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.y -= cell * 1.5f * dt
            f.life -= dt
            if (f.life <= 0f) fit.remove()
        }

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
            play("shoot", 0.35f)
        }

        updateBullets(dt)
        stepCentipede(dt)
        updateSpider(dt)
        updateFlea(dt)
        updateScorp(dt)
        checkPlayerHit()
    }

    private fun addScore(n: Int, x: Float, y: Float) {
        score += n
        floats.add(FT(x, y, "+$n"))
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
                    if (m.hp <= 0) {
                        mush.remove(key(c, r))
                        addScore(1, cellX(c), cellY(r))
                        burst(cellX(c), cellY(r), Color.rgb(255, 120, 0), 7)
                        play("mushbrk", 0.5f)
                    } else {
                        burst(cellX(c), cellY(r), Color.rgb(255, 170, 80), 3, 2f)
                        play("mush", 0.4f)
                    }
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
                        val pts = if (s.isHead) 100 else 10
                        addScore(pts, cellX(s.col), cellY(s.row))
                        burst(cellX(s.col), cellY(s.row), if (s.isHead) Color.rgb(190, 255, 80) else Color.rgb(70, 200, 70), 9)
                        play(if (s.isHead) "head" else "kill", 0.6f)
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
                    play("wave", 0.6f)
                    floats.add(FT(fieldLeft + cell * COLS / 2, fieldTop + cell * 8, "WAVE $wave", 1.2f))
                }
                continue
            }

            // spider / flea hit
            val sp2 = spider
            if (sp2 != null && abs(b[0] - sp2[0]) < cell * 0.5f && abs(b[1] - sp2[1]) < cell * 0.5f) {
                val dRows = (zoneBottomY() - sp2[1]) / cell
                val pts = if (dRows < 1.5f) 900 else if (dRows < 3.5f) 600 else 300
                addScore(pts, sp2[0], sp2[1])
                burst(sp2[0], sp2[1], Color.rgb(255, 70, 200), 12)
                play("spider", 0.65f)
                spider = null
                it.remove()
                continue
            }
            val fl = flea
            if (fl != null && abs(b[0] - fl[0]) < cell * 0.4f && abs(b[1] - fl[1]) < cell * 0.4f) {
                fleaHp--
                burst(fl[0], fl[1], Color.rgb(190, 80, 255), 4, 2f)
                play("mush", 0.4f)
                if (fleaHp <= 0) {
                    addScore(200, fl[0], fl[1])
                    burst(fl[0], fl[1], Color.rgb(190, 80, 255), 9)
                    play("flea", 0.6f)
                    flea = null
                }
                it.remove()
            }
            val sc = scorp
            if (sc != null && abs(b[0] - sc[0]) < cell * 0.6f && abs(b[1] - sc[1]) < cell * 0.55f) {
                addScore(1000, sc[0], sc[1])
                burst(sc[0], sc[1], Color.rgb(255, 220, 60), 14)
                play("scorp", 0.65f)
                scorp = null
                scorpTimer = 8f + Random.nextFloat() * 6f
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
            if (chain.isEmpty()) continue
            val head = chain[0]
            var nc = head.col
            var nr = head.row
            if (head.poisoned) {
                // poisoned head: dives straight down, chewing through anything
                nr = head.row + 1
                mush.remove(key(head.col, nr))
                if (nr >= ROWS - 1) head.poisoned = false
            } else {
                nc += head.dir
                val target = mush[key(nc, nr)]
                if (nc < 0 || nc >= COLS || target != null) {
                    if (target != null && target.poisoned) {
                        // eats the poisoned mushroom and goes crazy
                        mush.remove(key(nc, nr))
                        head.poisoned = true
                        play("poison", 0.5f)
                    } else {
                        head.dir = -head.dir
                        nc = head.col
                        nr += 1
                    }
                }
                if (nr > ROWS - 1) {
                    nr = 0
                    head.isHead = true
                }
            }
            var pc = head.col
            var pr = head.row
            head.col = nc
            head.row = nr
            // the body follows the head's exact trail, arcade style
            for (i in 1 until chain.size) {
                val s = chain[i]
                val tc = pc
                val tr = pr
                pc = s.col
                pr = s.row
                s.col = tc
                s.row = tr
                s.dir = chain[i - 1].dir
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
            if (mush.remove(key(c, r)) != null) burst(cellX(c), cellY(r), Color.rgb(255, 120, 0), 4, 2f)
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
                play("flea", 0.35f)
            }
        }
    }

    private fun updateScorp(dt: Float) {
        scorpTimer -= dt
        val sc = scorp
        if (sc == null) {
            if (scorpTimer <= 0f && wave >= 3) {
                scorpTimer = 8f + Random.nextFloat() * 6f
                val fromLeft = Random.nextBoolean()
                val x = if (fromLeft) fieldLeft - cell else fieldLeft + cell * COLS
                val y = cellY(Random.nextInt(4, ROWS - ZONE_ROWS - 1))
                val dx = (if (fromLeft) 1 else -1) * cell * 5f
                scorp = floatArrayOf(x, y, dx)
                play("poison", 0.35f)
            }
            return
        }
        sc[0] += sc[2] * dt
        if (sc[0] < fieldLeft - cell * 1.5f || sc[0] > fieldLeft + cell * (COLS + 1.5f)) {
            scorp = null
            scorpTimer = 8f + Random.nextFloat() * 6f
            return
        }
        val c = ((sc[0] - fieldLeft) / cell).toInt()
        val r = ((sc[1] - fieldTop) / cell).toInt()
        if (c in 0 until COLS && r in 0 until ROWS) {
            val m = mush[key(c, r)]
            if (m != null && !m.poisoned) {
                m.poisoned = true
                play("poison", 0.45f)
                burst(cellX(c), cellY(r), Color.rgb(200, 120, 255), 5, 2f)
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
        val sc = scorp
        if (!hit && sc != null && abs(px - sc[0]) < cell * 0.6f && abs(py - sc[1]) < cell * 0.55f) hit = true
        if (hit) {
            state = STATE_DYING
            deathT = 1.1f
            shakeT = 0.5f
            bullets.clear()
            touching = false
            burst(px, py, Color.CYAN, 22, 6f)
            burst(px, py, Color.WHITE, 10, 4f)
            play("die", 0.8f)
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
        canvas.save()
        if (shakeT > 0f) {
            val k = shakeT * cell * 0.5f
            canvas.translate((Random.nextFloat() - 0.5f) * k, (Random.nextFloat() - 0.5f) * k)
        }

        // nebula
        pNeb1?.let { canvas.drawRect(0f, 0f, screenW, screenH, it) }
        pNeb2?.let { canvas.drawRect(0f, 0f, screenW, screenH, it) }

        // stars
        for (s in stars) {
            pFill.alpha = (50 + 140 * abs(sin(time * 1.5f + s[2] * 6.28f))).toInt()
            pFill.color = Color.WHITE
            canvas.drawCircle(s[0], s[1], 1.5f, pFill)
        }
        pFill.alpha = 255

        // mushrooms
        for (m in mush.values) {
            val s = (if (m.poisoned) sprMushP else sprMush)[m.hp.coerceIn(1, 4)] ?: continue
            s.draw(canvas, cellX(m.col), cellY(m.row), cell * 1.35f, pSprite)
        }

        // centipede (undulating)
        for (chain in chains) {
            for (i in chain.size - 1 downTo 0) {
                val s = chain[i]
                val x = cellX(s.col)
                val y = cellY(s.row) + sin(time * 9f + i * 1.15f) * cell * 0.05f
                if (s.isHead) {
                    sprHead?.draw(canvas, x, y, cell * 1.28f, pSprite, flipH = s.dir < 0)
                } else {
                    sprBody?.draw(canvas, x, y, cell * 1.28f, pSprite)
                }
            }
        }

        // spider (animated)
        spider?.let { sp2 ->
            val fr = sprSpider[((time * 9f).toInt() % 2)] ?: return@let
            fr.draw(canvas, sp2[0], sp2[1], cell * 2.0f, pSprite)
        }

        // flea (animated)
        flea?.let {
            val fr = sprFlea[((time * 7f).toInt() % 2)] ?: return@let
            fr.draw(canvas, it[0], it[1], cell * 1.3f, pSprite)
        }

        // scorpion (animated, faces travel direction)
        scorp?.let {
            val fr = sprScorp[((time * 6f).toInt() % 2)] ?: return@let
            fr.draw(canvas, it[0], it[1], cell * 1.9f, pSprite, flipH = it[2] < 0)
        }

        // bullets (glowing)
        pFill.color = Color.WHITE
        for (b in bullets) {
            pFill.alpha = 90
            pFill.color = Color.rgb(120, 220, 255)
            canvas.drawCircle(b[0], b[1], 5f, pFill)
            pFill.alpha = 255
            pFill.color = Color.WHITE
            canvas.drawRect(b[0] - 2f, b[1] - cell * 0.35f, b[0] + 2f, b[1] + cell * 0.35f, pFill)
        }

        // particles
        for (q in parts) {
            pFill.alpha = (255 * (q.life / 0.7f).coerceIn(0f, 1f)).toInt()
            pFill.color = q.col
            canvas.drawCircle(q.x, q.y, cell * 0.09f, pFill)
        }
        pFill.alpha = 255

        // player
        if (state == STATE_PLAYING || state == STATE_READY) {
            val blink = invuln > 0f && ((invuln * 8f).toInt() % 2 == 0)
            if (!blink) sprShip?.draw(canvas, px, py, cell * 1.4f, pSprite)
        }

        // floating scores
        for (f in floats) {
            pFloat.alpha = (255 * (f.life / 0.8f).coerceIn(0f, 1f)).toInt()
            pFloat.textAlign = Paint.Align.CENTER
            canvas.drawText(f.text, f.x, f.y, pFloat)
        }
        pFloat.alpha = 255
        pFloat.textAlign = Paint.Align.LEFT

        canvas.restore()

        drawHud(canvas)
        drawOverlays(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        pText.color = Color.WHITE
        canvas.drawText("SCORE %05d".format(score), 16f, 46f, pText)
        canvas.drawText("HI %05d".format(highScore), screenW / 2f - 110f, 46f, pText)
        pText.color = Color.rgb(255, 200, 60)
        canvas.drawText("WAVE $wave", screenW - 210f, 46f, pText)
        for (i in 0 until lives) {
            val x = 30f + i * 44f
            val y = screenH - 26f
            sprShip?.draw(canvas, x, y, cell * 0.9f, pSprite)
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
