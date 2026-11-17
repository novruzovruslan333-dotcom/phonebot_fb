package com.phonebot
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Config.isFirstRun(ctx))
            ctx.startForegroundService(Intent(ctx, BotService::class.java))
    }
}
