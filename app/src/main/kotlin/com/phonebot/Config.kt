package com.phonebot
import android.content.Context

object Config {
    private const val P = "phonebot"
    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun getDevId(c: Context): String {
        var id = sp(c).getString("dev_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-","").take(12)
            sp(c).edit().putString("dev_id", id).apply()
        }
        return id
    }

    fun isFirstRun(c: Context) = !sp(c).getBoolean("started", false)
    fun markStarted(c: Context) = sp(c).edit().putBoolean("started", true).apply()
}
