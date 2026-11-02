package com.example.smsexporter.spike

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

// SPIKE: no-op components. They exist only so Android lets this app hold the SMS role.
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("SPIKE", "SMS_DELIVER received while default; ignored (spike)")
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("SPIKE", "WAP_PUSH_DELIVER received while default; ignored (spike)")
    }
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
