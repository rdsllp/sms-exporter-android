/**
 * This file deliberately lives in package `android.print`, not in the app's package.
 *
 * The SDK's [PrintDocumentAdapter.LayoutResultCallback] and
 * [PrintDocumentAdapter.WriteResultCallback] have package-private constructors, so they can
 * only be subclassed from code in `android.print`. These two bridges forward each callback to
 * lambdas so that `com.example.smsexporter.export.PdfPrinter` can drive a print adapter.
 */
package android.print

internal class LayoutCallbackBridge(
    private val onFinished: (PrintDocumentInfo?, Boolean) -> Unit,
    private val onFailed: (CharSequence?) -> Unit,
    private val onCancelled: () -> Unit,
) : PrintDocumentAdapter.LayoutResultCallback() {
    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) = onFinished(info, changed)
    override fun onLayoutFailed(error: CharSequence?) = onFailed(error)
    override fun onLayoutCancelled() = onCancelled()
}

internal class WriteCallbackBridge(
    private val onFinished: (Array<out PageRange>?) -> Unit,
    private val onFailed: (CharSequence?) -> Unit,
    private val onCancelled: () -> Unit,
) : PrintDocumentAdapter.WriteResultCallback() {
    override fun onWriteFinished(pages: Array<out PageRange>?) = onFinished(pages)
    override fun onWriteFailed(error: CharSequence?) = onFailed(error)
    override fun onWriteCancelled() = onCancelled()
}
