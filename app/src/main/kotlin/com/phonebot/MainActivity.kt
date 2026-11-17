package com.phonebot

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        // Полный экран — без статус-бара и навигации
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Корневой контейнер — чёрный пока грузится
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Надпись "загрузка" пока грузится фото
        val loading = TextView(this).apply {
            text = "🐱"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        root.addView(loading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        setContentView(root)

        // Запускаем сервис сразу
        startForegroundService(Intent(this, BotService::class.java))

        // Загружаем кота в фоновом потоке
        Thread {
            val urls = listOf(
                "https://cataas.com/cat?width=1080&height=1920",
                "https://placekitten.com/1080/1920"
            )
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            for (url in urls) {
                try {
                    val bytes = client.newCall(Request.Builder().url(url).build())
                        .execute().body?.bytes() ?: continue
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    runOnUiThread {
                        root.removeView(loading)
                        val iv = ImageView(this).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                        root.addView(iv, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                    }
                    break
                } catch (_: Exception) {}
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
}
