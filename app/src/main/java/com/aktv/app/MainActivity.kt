package com.aktv.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VH(v: View) : RecyclerView.ViewHolder(v)

class MainActivity : AppCompatActivity() {
    data class Channel(val id: String, val name: String, val category: String, val logo: String)
    data class Site(val name: String, val url: String, val c1: Int, val c2: Int)

    // ➜ ADD MORE WEBSITES HERE (name, link, gradient colours)
    private val SITES = listOf(
        Site("Net77", "https://net77.cc/home", 0xFFE50914.toInt(), 0xFF5A0A12.toInt()),
        Site("AethoFlix", "https://www.aethoflix.world/", 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt())
    )
    private val FAV = "★ Favorites"
    private val BASE = "https://livetgtv.lovable.app/api/public/channels"
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(3)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }

    private var all = listOf<Channel>(); private var shown = listOf<Channel>()
    private var cats = listOf<String>(); private var selCat = 1
    private var favs = setOf<String>()
    private var tab = 0; private var current = -1; private var retries = 0
    private var player: ExoPlayer? = null

    private lateinit var grid: RecyclerView; private lateinit var chips: RecyclerView
    private lateinit var status: TextView; private lateinit var layer: View
    private lateinit var pv: PlayerView; private lateinit var now: TextView; private lateinit var spinner: View
    private lateinit var segLive: TextView; private lateinit var segSites: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid); chips = findViewById(R.id.chips); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView)
        now = findViewById(R.id.nowPlaying); spinner = findViewById(R.id.spinner)
        segLive = findViewById(R.id.segLive); segSites = findViewById(R.id.segSites)
        favs = prefs.getStringSet("favs", emptySet())!!.toSet()

        grid.layoutManager = GridLayoutManager(this, 5); grid.adapter = chAdapter
        chips.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false); chips.adapter = chipAdapter
        listOf(segLive to 0, segSites to 1).forEach { (tv, i) ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, f -> if (f) switchTab(i); styleSegs() }
        }
        styleSegs(); loadChannels()

        val splash = findViewById<View>(R.id.splash); val logo = findViewById<View>(R.id.splashLogo)
        logo.scaleX = 0.85f; logo.scaleY = 0.85f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(DecelerateInterpolator()).start()
        splash.postDelayed({ splash.animate().alpha(0f).setDuration(500).withEndAction { splash.visibility = View.GONE; grid.requestFocus() }.start() }, 1800)
    }

    // ---------- helpers ----------
    private fun styleSegs() {
        listOf(segLive to 0, segSites to 1).forEach { (tv, i) ->
            tv.isSelected = tab == i
            tv.setTextColor(if (tv.isFocused) Color.BLACK else Color.WHITE)
        }
    }
    private fun lift(v: View) = v.setOnFocusChangeListener { x, f ->
        val s = if (f) 1.1f else 1f
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).translationZ(if (f) 28f else 0f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun enter(v: View, i: Int) {
        v.alpha = 0f; v.translationY = 40f
        v.animate().setStartDelay(minOf(i, 10) * 30L).alpha(1f).translationY(0f)
            .setDuration(380).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- data ----------
    private fun loadChannels() {
        status.text = "Loading channels…"
        io.execute {
            try {
                val arr = JSONObject(get(BASE)).getJSONArray("channels")
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Channel(o.getString("id"), o.getString("name"), o.optString("category", "Other"), o.optString("logo", ""))
                }
                runOnUiThread {
                    all = list
                    cats = listOf(FAV, "All") + list.map { it.category }.distinct().sorted()
                    chipAdapter.notifyDataSetChanged()
                    applyFilter(if (favs.isNotEmpty()) 0 else 1)
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Couldn't load channels: ${e.message}" } }
        }
    }

    private fun applyFilter(i: Int) {
        selCat = i
        shown = when (cats[i]) { FAV -> all.filter { it.id in favs }; "All" -> all; else -> all.filter { it.category == cats[i] } }
        status.text = if (cats[i] == FAV && shown.isEmpty()) "No favorites yet — hold OK on any channel to add it ♥"
                      else "${shown.size} channels  •  hold OK on a channel to favorite it"
        chAdapter.notifyDataSetChanged(); chipAdapter.notifyDataSetChanged()
    }

    private fun toggleFav(c: Channel, pos: Int) {
        val s = favs.toMutableSet(); val added = s.add(c.id); if (!added) s.remove(c.id)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to Favorites ♥" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        if (cats[selCat] == FAV) applyFilter(selCat) else chAdapter.notifyItemChanged(pos, "fav")
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        tab = t; styleSegs()
        grid.animate().alpha(0f).setDuration(160).withEndAction {
            val lm = grid.layoutManager as GridLayoutManager
            if (t == 0) { chips.visibility = View.VISIBLE; lm.spanCount = 5; grid.adapter = chAdapter; applyFilter(selCat) }
            else { chips.visibility = View.GONE; lm.spanCount = 2; grid.adapter = siteAdapter; status.text = "Pick a website — use arrows to move the pointer, OK to click" }
            grid.animate().alpha(1f).setDuration(280).start()
        }.start()
    }

    // ---------- playback (same engine that worked) ----------
    private fun hexToB64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        if (layer.visibility != View.VISIBLE) { layer.alpha = 0f; layer.visibility = View.VISIBLE; layer.animate().alpha(1f).setDuration(250).start() }
        spinner.visibility = View.VISIBLE; now.text = ch.name
        releasePlayer()
        io.execute {
            try {
                val o = JSONObject(get("$BASE/${ch.id}"))
                val url = o.getJSONArray("sources").getString(0); val drm = o.optJSONObject("drm")
                runOnUiThread { if (shown.getOrNull(current)?.id == ch.id) start(url, drm) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show(); spinner.visibility = View.GONE }
            }
        }
    }
    private fun start(url: String, drm: JSONObject?) {
        val token = Regex("__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)
        val hdrs = HashMap<String, String>(); if (token != null) hdrs["Cookie"] = "__hdnea__=$token"
        val http = DefaultHttpDataSource.Factory().setUserAgent("plaYtv/7.1.5 (Linux;Android 13) ExoPlayerLib/2.11.7")
            .setDefaultRequestProperties(hdrs).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val factory = DashMediaSource.Factory(http)
        if (drm != null && drm.has("keyId")) {
            val lic = """{"keys":[{"kty":"oct","k":"${hexToB64Url(drm.getString("key"))}","kid":"${hexToB64Url(drm.getString("keyId"))}"}],"type":"temporary"}"""
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true).build(LocalMediaDrmCallback(lic.toByteArray()))
            factory.setDrmSessionManagerProvider { mgr }
        }
        val item = MediaItem.Builder().setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8000).build()).build()
        val p = ExoPlayer.Builder(this).build()
        p.setMediaSource(factory.createMediaSource(item)); p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) { spinner.visibility = View.GONE; retries = 0 }
                if (s == Player.STATE_BUFFERING) spinner.visibility = View.VISIBLE
            }
            override fun onPlayerError(e: PlaybackException) {
                if (retries++ < 3) play(current) else {
                    spinner.visibility = View.GONE
                    val c = e.cause
                    val extra = if (c is HttpDataSource.InvalidResponseCodeException) " HTTP ${c.responseCode}" else ""
                    Toast.makeText(this@MainActivity, "Playback error: ${e.errorCodeName}$extra", Toast.LENGTH_LONG).show()
                }
            }
        })
        pv.player = p; player = p; p.prepare()
        now.alpha = 1f; now.postDelayed({ if (player === p) now.animate().alpha(0f).setDuration(500).start() }, 4000)
    }
    private fun releasePlayer() { pv.player = null; player?.release(); player = null }
    private fun closePlayer() {
        releasePlayer(); retries = 0
        if (layer.visibility == View.VISIBLE) layer.animate().alpha(0f).setDuration(200).withEndAction { layer.visibility = View.GONE; grid.requestFocus() }.start()
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { now.alpha = 1f; return true }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onStop() { super.onStop(); releasePlayer(); layer.visibility = View.GONE }

    // ---------- adapters ----------
    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false).also { lift(it) })
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) { h.itemView.findViewById<View>(R.id.fav).visibility = if (shown[i].id in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val c = shown[i]; val v = h.itemView
            v.findViewById<TextView>(R.id.name).text = c.name
            v.findViewById<ImageView>(R.id.logo).load(c.logo)
            v.findViewById<View>(R.id.fav).visibility = if (c.id in favs) View.VISIBLE else View.GONE
            v.setOnClickListener { retries = 0; play(h.bindingAdapterPosition) }
            v.setOnLongClickListener { toggleFav(c, h.bindingAdapterPosition); true }
            enter(v, i)
        }
    }
    private val siteAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = SITES.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_site, p, false).also { lift(it) })
        override fun onBindViewHolder(h: VH, i: Int) {
            val s = SITES[i]; val v = h.itemView
            v.findViewById<View>(R.id.siteBox).background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(s.c1, s.c2)).apply { cornerRadius = 48f }
            v.findViewById<TextView>(R.id.siteName).text = s.name
            v.findViewById<TextView>(R.id.siteUrl).text = s.url.removePrefix("https://")
            v.setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("url", s.url)) }
            enter(v, i)
        }
    }
    private val chipAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(-2, -2).apply { setMargins(0, 0, 14, 0) }
                setPadding(38, 16, 38, 16); textSize = 14f; isFocusable = true; isClickable = true
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setBackgroundResource(R.drawable.chip_bg)
            }
            return VH(tv)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val tv = h.itemView as TextView
            tv.text = cats[i]; tv.isSelected = i == selCat; tv.setTextColor(Color.WHITE)
            tv.setOnFocusChangeListener { _, f -> tv.setTextColor(if (f) Color.BLACK else Color.WHITE)
                tv.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(180).start() }
            tv.setOnClickListener { applyFilter(h.bindingAdapterPosition) }
        }
    }
}
