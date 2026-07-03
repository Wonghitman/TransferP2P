package com.oneturn.transfer.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * Android 10+ (API 29+): writing via MediaStore does NOT need storage permission.
 * Only Android 9 and below need WRITE_EXTERNAL_STORAGE for legacy public Downloads.
 */
object StoragePermission {
    fun needsLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasLegacyWritePermission(activity: ComponentActivity): Boolean {
        if (!needsLegacyWritePermission()) return true
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class LegacyStoragePermissionRequester(
    private val activity: ComponentActivity,
) {
    private var pending: CompletableDeferred<Boolean>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pending?.complete(granted)
        pending = null
    }

    suspend fun ensureGranted(): Boolean {
        if (!StoragePermission.needsLegacyWritePermission()) return true
        if (StoragePermission.hasLegacyWritePermission(activity)) return true
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return deferred.await()
    }
}
