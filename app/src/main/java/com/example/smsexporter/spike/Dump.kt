package com.example.smsexporter.spike

import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import java.io.File

// SPIKE: throwaway. Counts what this app can see in the Telephony provider and writes a report.
object Dump {
    private fun count(ctx: Context, uri: Uri, where: String? = null): Int =
        ctx.contentResolver.query(uri, arrayOf("_id"), where, null, null)?.use { it.count } ?: -1

    private fun tally(ctx: Context, uri: Uri, cols: Array<String>, where: String? = null): Map<String, Int> {
        val m = HashMap<String, Int>()
        ctx.contentResolver.query(uri, cols, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                val key = cols.indices.joinToString(", ") { "${cols[it]}=${c.getString(it)}" }
                m[key] = (m[key] ?: 0) + 1
            }
        }
        return m
    }

    fun run(ctx: Context): String {
        val sb = StringBuilder()
        val rm = ctx.getSystemService(RoleManager::class.java)
        val isDefault = rm?.isRoleHeld(RoleManager.ROLE_SMS) == true
        sb.appendLine("defaultSmsApp=${Telephony.Sms.getDefaultSmsPackage(ctx)} thisAppHoldsRole=$isDefault")
        val t0 = System.currentTimeMillis()
        sb.appendLine("sms=${count(ctx, Telephony.Sms.CONTENT_URI)}")
        sb.appendLine("mms=${count(ctx, Telephony.Mms.CONTENT_URI)}")
        sb.appendLine("mms_rcs(tr_id proto:)=${count(ctx, Telephony.Mms.CONTENT_URI, "tr_id LIKE 'proto:%'")}")
        sb.appendLine("mms_parts=${count(ctx, Uri.parse("content://mms/part"))}")
        sb.appendLine("threads=${count(ctx, Uri.parse("content://mms-sms/conversations?simple=true"))}")
        sb.appendLine("sum(message_count)=" + run {
            var s = 0L
            ctx.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("message_count"), null, null, null)?.use { c ->
                while (c.moveToNext()) s += c.getLong(0)
            }
            s
        })
        sb.appendLine("-- mms by creator/box/ct_t --")
        tally(ctx, Telephony.Mms.CONTENT_URI, arrayOf("creator", "msg_box", "ct_t")).toSortedMap().forEach { (k, v) -> sb.appendLine("$v  $k") }
        sb.appendLine("-- sms by creator/type --")
        tally(ctx, Telephony.Sms.CONTENT_URI, arrayOf("creator", "type")).toSortedMap().forEach { (k, v) -> sb.appendLine("$v  $k") }
        sb.appendLine("-- rcs rows per thread (top) --")
        tally(ctx, Telephony.Mms.CONTENT_URI, arrayOf("thread_id"), "tr_id LIKE 'proto:%'")
            .entries.sortedByDescending { it.value }.take(15).forEach { sb.appendLine("${it.value}  ${it.key}") }
        sb.appendLine("-- all mms columns --")
        ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)?.use { c ->
            sb.appendLine(c.columnNames.joinToString(","))
        }
        sb.appendLine("-- all sms columns --")
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)?.use { c ->
            sb.appendLine(c.columnNames.joinToString(","))
        }
        sb.appendLine("elapsedMs=${System.currentTimeMillis() - t0}")
        val out = sb.toString()
        File(ctx.getExternalFilesDir(null), if (isDefault) "dump_default.txt" else "dump_nondefault.txt").writeText(out)
        return out
    }
}
