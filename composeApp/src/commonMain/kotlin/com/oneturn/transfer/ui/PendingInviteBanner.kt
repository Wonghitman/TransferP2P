package com.oneturn.transfer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.oneturn.transfer.api.TransferSessionManager
import kotlinx.coroutines.launch

/**
 * Global dialog for incoming transfer invites. Mount once at the app root so it
 * surfaces on any screen (match / chat / devices).
 */
@Composable
fun PendingInviteDialog(
    sessionManager: TransferSessionManager,
    modifier: Modifier = Modifier,
) {
    val invite by sessionManager.pendingInvite.collectAsState()
    val pending = invite
    val scope = rememberCoroutineScope()
    pending ?: return

    AlertDialog(
        onDismissRequest = { sessionManager.rejectPendingInvite() },
        title = { Text("收到连接请求") },
        text = { Text("${pending.fromDisplayName} 请求与你连接，是否同意？") },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch { sessionManager.acceptPendingInvite() }
                },
            ) {
                Text("同意")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { sessionManager.rejectPendingInvite() },
            ) {
                Text("拒绝")
            }
        },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
