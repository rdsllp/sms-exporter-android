package com.example.smsexporter.role

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

// No-op components. They exist only so Android lets this app hold the SMS role.
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
