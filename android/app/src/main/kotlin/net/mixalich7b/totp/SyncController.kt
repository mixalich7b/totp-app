package net.mixalich7b.totp

import android.app.Activity
import android.app.AlertDialog

internal class SyncController(
    private val activity: Activity,
    private val entryStore: LocalEntryStore,
    private val screen: MainScreen,
    private val onStorageError: ErrorHandler,
    private val onSyncError: ErrorHandler,
) : AutoCloseable {
    private lateinit var syncManager: GarminSyncManager

    @Volatile
    private var closed = false

    init {
        syncManager = GarminSyncManager(
            activity,
            statusChanged = { text ->
                activity.runOnUiThread {
                    if (!closed) screen.showStatus(text)
                }
            },
            cancellationChanged = { canCancel ->
                activity.runOnUiThread {
                    if (!closed && syncManager.isSyncing()) {
                        screen.setSyncButton(
                            if (canCancel) {
                                R.string.action_cancel_sync
                            } else {
                                R.string.action_finishing_sync
                            },
                            enabled = canCancel,
                        )
                    }
                }
            },
        )
    }

    fun onSyncPressed() {
        if (syncManager.isSyncing()) {
            syncManager.cancel()
        } else {
            synchronize()
        }
    }

    fun confirmClearWatch() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_clear_watch)
            .setMessage(R.string.message_clear_watch)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear_watch) { _, _ ->
                clearWatch()
            }
            .show()
    }

    private fun clearWatch() {
        screen.setMutationControlsEnabled(false)
        screen.setSyncButton(
            R.string.action_finishing_sync,
            enabled = false,
        )
        syncManager.clearWatch { result ->
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                screen.setMutationControlsEnabled(true)
                screen.setSyncButton(R.string.action_sync, enabled = true)
                result.onSuccess(screen::showStatus)
                    .onFailure { error ->
                        onSyncError(error) {
                            clearWatch()
                        }
                    }
            }
        }
    }

    private fun synchronize() {
        screen.setMutationControlsEnabled(false)
        screen.setSyncButton(R.string.action_cancel_sync, enabled = false)
        entryStore.snapshot(
            onSuccess = { snapshot ->
                screen.setSyncButton(R.string.action_cancel_sync, enabled = true)
                startSync(snapshot)
            },
            onFailure = { error ->
                screen.setMutationControlsEnabled(true)
                screen.setSyncButton(R.string.action_retry_sync, enabled = true)
                onStorageError(error) {
                    synchronize()
                }
            },
            onRetry = ::showErrorStatus,
        )
    }

    private fun startSync(snapshot: LocalSnapshot) {
        try {
            syncManager.sync(snapshot.entries, snapshot.revision) { result ->
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    result.onSuccess { status ->
                        screen.setMutationControlsEnabled(true)
                        screen.setSyncButton(R.string.action_sync, enabled = true)
                        screen.showStatus(status)
                    }.onFailure { error ->
                        screen.setMutationControlsEnabled(true)
                        if (error is SyncCancelledException) {
                            screen.setSyncButton(R.string.action_sync, enabled = true)
                            screen.showStatus(error.userMessage())
                        } else {
                            screen.setSyncButton(R.string.action_retry_sync, enabled = true)
                            onSyncError(error) {
                                synchronize()
                            }
                        }
                    }
                }
            }
        } finally {
            snapshot.entries.forEach { it.secret.fill(0) }
        }
    }

    private fun Throwable.userMessage() = message ?: javaClass.simpleName

    private fun showErrorStatus(error: Throwable) {
        screen.showStatus(R.string.error_status, error.userMessage())
    }

    override fun close() {
        closed = true
        syncManager.close()
    }
}
