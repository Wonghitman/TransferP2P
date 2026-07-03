package com.oneturn.transfer.platform

import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CompletableDeferred

actual class QrScanner(
    private val activity: ComponentActivity,
) {
    private var pendingResult: CompletableDeferred<String?>? = null

    private val launcher = activity.registerForActivityResult(ScanContract()) { result ->
        val deferred = pendingResult
        pendingResult = null
        deferred?.complete(result.contents)
    }

    actual suspend fun scanJoinUrl(): String? {
        val deferred = CompletableDeferred<String?>()
        pendingResult = deferred
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准发送方二维码")
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        }
        launcher.launch(options)
        return deferred.await()
    }
}
