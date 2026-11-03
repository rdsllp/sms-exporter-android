package com.example.smsexporter.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.LayoutCallbackBridge
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.WriteCallbackBridge
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Prints an HTML document to PDF through a WebView's PrintDocumentAdapter. */
class PdfPrinter(context: Context) {
    // Application context only, so a caller's Activity is never retained.
    private val appContext: Context = context.applicationContext

    suspend fun print(html: String, out: OutputStream) {
        try {
            withTimeout(TIMEOUT_MS) { printInternal(html, out) }
        } catch (e: TimeoutCancellationException) {
            throw IOException("PDF print timed out", e)
        }
    }

    private suspend fun printInternal(html: String, out: OutputStream) {
        var webView: WebView? = null
        try {
            val adapter = withContext(Dispatchers.Main) {
                val wv = WebView(appContext)
                webView = wv
                wv.settings.javaScriptEnabled = false
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                loadPage(wv, html)
                wv.createPrintDocumentAdapter(JOB_NAME)
            }
            withContext(Dispatchers.Main) { layout(adapter) }
            write(adapter, out)
        } finally {
            webView?.let { wv -> withContext(NonCancellable + Dispatchers.Main) { wv.destroy() } }
        }
    }

    /** Main thread. Resolves when the page has finished loading. */
    private suspend fun loadPage(wv: WebView, html: String) =
        suspendCancellableCoroutine { cont ->
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation { wv.stopLoading() }
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }

    /** Main thread. */
    private suspend fun layout(adapter: PrintDocumentAdapter) =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            // Bridge lives in android.print: the SDK callback constructors are package-private.
            val callback = LayoutCallbackBridge(
                onFinished = { _, _ -> if (cont.isActive) cont.resume(Unit) },
                onFailed = { msg ->
                    if (cont.isActive) cont.resumeWithException(IOException("PDF layout failed: $msg"))
                },
                onCancelled = {
                    if (cont.isActive) cont.resumeWithException(CancellationException("PDF layout cancelled"))
                },
            )
            adapter.onLayout(null, ATTRIBUTES, signal, callback, Bundle())
        }

    /**
     * Pipes the adapter's output into [out]: the write side is driven on Main, the read
     * side copies on IO. The write fd is closed once onWrite reports back so the reader
     * sees EOF; the reader is always awaited (or cancelled) before returning.
     */
    private suspend fun write(adapter: PrintDocumentAdapter, out: OutputStream) = coroutineScope {
        val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
        val reader = async(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { it.copyTo(out) }
        }
        try {
            withContext(Dispatchers.Main) {
                try {
                    suspendCancellableCoroutine { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        // Bridge lives in android.print (package-private SDK constructor).
                        val callback = WriteCallbackBridge(
                            onFinished = { _ -> if (cont.isActive) cont.resume(Unit) },
                            onFailed = { msg ->
                                if (cont.isActive) cont.resumeWithException(IOException("PDF write failed: $msg"))
                            },
                            onCancelled = {
                                if (cont.isActive) cont.resumeWithException(CancellationException("PDF write cancelled"))
                            },
                        )
                        adapter.onWrite(arrayOf(PageRange.ALL_PAGES), writeFd, signal, callback)
                    }
                } finally {
                    writeFd.close()
                }
            }
            reader.await()
        } catch (e: Throwable) {
            // The Main block may never have run (cancelled before dispatch), so close the
            // write end here too; that gives the reader EOF. Closing twice is harmless.
            runCatching { writeFd.close() }
            reader.cancel()
            runCatching { readFd.close() }
            throw e
        }
    }

    private companion object {
        const val JOB_NAME = "SmsExporter"
        const val TIMEOUT_MS = 60_000L
        val ATTRIBUTES: PrintAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
    }
}
