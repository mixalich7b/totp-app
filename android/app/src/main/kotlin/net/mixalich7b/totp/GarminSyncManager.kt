package net.mixalich7b.totp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import java.util.UUID

internal class SyncCancelledException(message: String) : Exception(message)

private enum class PendingOperation { SNAPSHOT, CLEAR_WATCH }

private object SyncLog {
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.w(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
    }
}

class GarminSyncManager(
    context: Context,
    private val statusChanged: (String) -> Unit,
    private val cancellationChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectIq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
    private val watchApp = IQApp(APP_UUID)
    private val timeoutHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var ready = false
    @Volatile
    private var pendingCompletion: ((Result<String>) -> Unit)? = null
    @Volatile
    private var pendingRevision = 0L
    @Volatile
    private var pendingTransferId: String? = null
    private var pendingOperation: PendingOperation? = null
    private var pendingDeviceId: Long? = null
    private var cancelAllowed = false
    private var timeoutRunnable: Runnable? = null

    private val applicationListener = object : ConnectIQ.IQApplicationEventListener {
        override fun onMessageReceived(
            device: IQDevice,
            app: IQApp,
            messages: MutableList<Any>,
            status: ConnectIQ.IQMessageStatus,
        ) {
            if (!isActiveDevice(device.deviceIdentifier)) {
                SyncLog.i(TAG, "Ignoring response from inactive device")
                return
            }
            SyncLog.i(TAG, "Receive callback: status=$status, messages=${messages.size}, transfer=${pendingTransferId ?: "none"}")
            if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                SyncLog.w(TAG, "Receive failed: status=$status, transfer=${pendingTransferId ?: "none"}")
                finish(Result.failure(syncFailure(R.string.sync_receive_failed, messageStatusText(status))))
                return
            }
            messages.filterIsInstance<Map<*, *>>().forEach { message ->
                val type = normalizeMessageType(message["t"])
                val responseTransferId = message["x"]?.toString()
                SyncLog.i(TAG, "Received message: type=${type ?: "missing"}, transfer=${pendingTransferId ?: "none"}")
                if (!responseMatchesTransfer(responseTransferId, pendingTransferId)) {
                    SyncLog.i(TAG, "Ignoring response from inactive transfer=$responseTransferId")
                    return@forEach
                }
                when (type) {
                    "a" -> {
                        val revision = (message["r"] as? Number)?.toLong() ?: return@forEach
                        if (pendingOperation == PendingOperation.SNAPSHOT && revision == pendingRevision) {
                            SyncLog.i(TAG, "ACK accepted: revision=$revision, transfer=${pendingTransferId ?: "none"}")
                            finish(Result.success(appContext.getString(R.string.sync_complete, revision)))
                        } else {
                            SyncLog.w(TAG, "ACK ignored: revision=$revision, expected=$pendingRevision, transfer=${pendingTransferId ?: "none"}")
                        }
                    }
                    "z" -> {
                        if (pendingOperation == PendingOperation.CLEAR_WATCH) {
                            SyncLog.i(TAG, "Watch clear ACK accepted: transfer=${pendingTransferId ?: "none"}")
                            finish(Result.success(appContext.getString(R.string.watch_clear_complete)))
                        }
                    }
                    "e" -> {
                        val errorCode = message["m"]?.toString()
                        SyncLog.w(TAG, "Watch rejected transfer=${pendingTransferId ?: "none"}, category=${watchErrorLogCategory(errorCode)}")
                        finish(Result.failure(syncFailure(watchErrorMessageRes(errorCode))))
                    }
                    else -> SyncLog.w(TAG, "Ignoring unsupported response type=${type ?: "missing"}, transfer=${pendingTransferId ?: "none"}")
                }
            }
        }
    }

    init {
        connectIq.initialize(appContext, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                ready = true
                SyncLog.i(TAG, "Connect IQ SDK ready")
                statusChanged(appContext.getString(R.string.sync_sdk_ready))
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                SyncLog.e(TAG, "Connect IQ SDK initialization failed: $status")
                statusChanged(appContext.getString(sdkErrorMessageRes(status)))
            }

            override fun onSdkShutDown() {
                ready = false
                SyncLog.i(TAG, "Connect IQ SDK shut down")
                statusChanged(appContext.getString(R.string.sync_sdk_shutdown))
                finish(Result.failure(syncFailure(R.string.sync_sdk_shutdown)))
            }
        })
    }

    @Synchronized
    fun sync(entries: List<TotpEntry>, revision: Long, completion: (Result<String>) -> Unit) {
        SyncLog.i(TAG, "Sync requested: revision=$revision, entries=${entries.size}")
        if (!ready) {
            SyncLog.w(TAG, "Sync rejected: Connect IQ SDK is not ready")
            completion(Result.failure(IllegalStateException(appContext.getString(R.string.sync_sdk_not_ready))))
            return
        }
        val connectedDevices = runCatching { connectIq.connectedDevices }.getOrElse {
            SyncLog.e(TAG, "Failed to query connected devices", it)
            completion(Result.failure(syncFailure(connectionExceptionMessageRes(it)))); return
        }
        val device = connectedDevices.firstOrNull()
        if (device == null) {
            SyncLog.w(TAG, "Sync rejected: no connected Garmin device")
            completion(Result.failure(syncFailure(R.string.sync_no_device)))
            return
        }
        if (pendingCompletion != null) {
            SyncLog.w(TAG, "Sync rejected: another transfer is active (${pendingTransferId ?: "unknown"})")
            completion(Result.failure(IllegalStateException(appContext.getString(R.string.sync_already_running))))
            return
        }

        try {
            connectIq.registerForAppEvents(device, watchApp, applicationListener)
            SyncLog.i(TAG, "Registered application event listener")
        } catch (error: Exception) {
            SyncLog.e(TAG, "Failed to register application event listener", error)
            completion(Result.failure(syncFailure(connectionExceptionMessageRes(error))))
            return
        }

        pendingCompletion = completion
        pendingRevision = revision
        val transferId = UUID.randomUUID().toString().take(12)
        pendingTransferId = transferId
        pendingOperation = PendingOperation.SNAPSHOT
        pendingDeviceId = device.deviceIdentifier
        cancelAllowed = true
        cancellationChanged(true)
        armTimeout()
        val messages = mutableListOf<Map<String, Any>>()
        messages += mapOf(
            "t" to "b", "v" to 1, "x" to transferId, "r" to revision,
            "n" to entries.size, "h" to SnapshotHasher.sha256(entries)
        )
        entries.forEachIndexed { index, entry ->
            messages += mapOf(
                "t" to "c", "x" to transferId, "q" to index,
                "e" to mapOf(
                    "i" to entry.id,
                    "n" to entry.displayName,
                    "s" to entry.secret.map { it.toInt() and 0xff },
                    "a" to entry.algorithm.wireValue,
                    "d" to entry.digits,
                    "p" to entry.periodSeconds,
                )
            )
        }
        messages += mapOf("t" to "m", "x" to transferId)
        SyncLog.i(TAG, "Transfer prepared: transfer=$transferId, revision=$revision, entries=${entries.size}, messages=${messages.size}")
        sendNext(device, messages, 0, entries.size, transferId)
    }

    @Synchronized
    fun clearWatch(completion: (Result<String>) -> Unit) {
        SyncLog.i(TAG, "Watch clear requested")
        if (!ready) {
            completion(Result.failure(syncFailure(R.string.sync_sdk_not_ready)))
            return
        }
        if (pendingCompletion != null) {
            completion(Result.failure(syncFailure(R.string.sync_already_running)))
            return
        }
        val device = runCatching {
            connectIq.connectedDevices.firstOrNull()
        }.getOrElse {
            SyncLog.e(TAG, "Failed to query connected devices for clear", it)
            completion(Result.failure(syncFailure(connectionExceptionMessageRes(it)))); return
        }
        if (device == null) {
            completion(Result.failure(syncFailure(R.string.sync_no_device)))
            return
        }
        try {
            connectIq.registerForAppEvents(device, watchApp, applicationListener)
        } catch (error: Exception) {
            SyncLog.e(TAG, "Failed to register listener for watch clear", error)
            completion(Result.failure(syncFailure(connectionExceptionMessageRes(error))))
            return
        }

        val transferId = UUID.randomUUID().toString().take(12)
        pendingCompletion = completion
        pendingTransferId = transferId
        pendingOperation = PendingOperation.CLEAR_WATCH
        pendingDeviceId = device.deviceIdentifier
        cancelAllowed = false
        cancellationChanged(false)
        statusChanged(appContext.getString(R.string.watch_clear_sending))
        armTimeout()
        val message = mapOf<String, Any>("t" to "d", "v" to 1, "x" to transferId)
        try {
            connectIq.sendMessage(device, watchApp, message, object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, app: IQApp, status: ConnectIQ.IQMessageStatus) {
                    if (!isActive(transferId)) return
                    SyncLog.i(TAG, "Watch clear send callback: status=$status, transfer=$transferId")
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        statusChanged(appContext.getString(R.string.watch_clear_waiting))
                        armTimeout()
                    } else {
                        finish(Result.failure(syncFailure(R.string.sync_transfer_failed, messageStatusText(status))))
                    }
                }
            })
        } catch (error: Exception) {
            SyncLog.e(TAG, "Exception while sending watch clear, transfer=$transferId", error)
            finish(Result.failure(syncFailure(connectionExceptionMessageRes(error))))
        }
    }

    private fun sendNext(
        device: IQDevice,
        messages: List<Map<String, Any>>,
        index: Int,
        entryCount: Int,
        transferId: String,
    ) {
        if (!isActive(transferId)) return
        if (index >= messages.size) {
            SyncLog.i(TAG, "All messages sent; waiting for ACK: transfer=${pendingTransferId ?: "none"}, revision=$pendingRevision")
            statusChanged(appContext.getString(R.string.sync_waiting_for_ack))
            armTimeout()
            return
        }
        val type = normalizeMessageType(messages[index]["t"]) ?: "missing"
        if (type == "m") {
            synchronized(this) { cancelAllowed = false }
            cancellationChanged(false)
        }
        statusChanged(
            when (type) {
                "b" -> appContext.getString(R.string.sync_preparing)
                "c" -> appContext.getString(R.string.sync_progress, index, entryCount)
                "m" -> appContext.getString(R.string.sync_committing)
                else -> appContext.getString(R.string.sync_transfering_entries, entryCount)
            }
        )
        SyncLog.i(TAG, "Sending message: type=$type, position=${index + 1}/${messages.size}, transfer=${pendingTransferId ?: "none"}")
        try {
            connectIq.sendMessage(device, watchApp, messages[index], object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, app: IQApp, status: ConnectIQ.IQMessageStatus) {
                    if (!isActive(transferId)) {
                        SyncLog.i(TAG, "Ignoring callback from inactive transfer=$transferId")
                        return
                    }
                    SyncLog.i(TAG, "Send callback: type=$type, position=${index + 1}/${messages.size}, status=$status, transfer=${pendingTransferId ?: "none"}")
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        armTimeout()
                        sendNext(device, messages, index + 1, entryCount, transferId)
                    } else {
                        SyncLog.w(TAG, "Transfer failed while sending type=$type: status=$status, transfer=${pendingTransferId ?: "none"}")
                        finish(Result.failure(syncFailure(R.string.sync_transfer_failed, messageStatusText(status))))
                    }
                }
            })
        } catch (error: Exception) {
            SyncLog.e(TAG, "Exception while sending type=$type, transfer=${pendingTransferId ?: "none"}", error)
            finish(Result.failure(syncFailure(connectionExceptionMessageRes(error))))
        }
    }

    @Synchronized
    fun isSyncing(): Boolean = pendingCompletion != null

    @Synchronized
    fun cancel(): Boolean {
        if (pendingCompletion == null || !cancelAllowed) return false
        SyncLog.i(TAG, "Sync canceled by user: transfer=${pendingTransferId ?: "none"}")
        finish(Result.failure(SyncCancelledException(appContext.getString(R.string.sync_canceled))))
        return true
    }

    @Synchronized
    private fun isActive(transferId: String): Boolean =
        pendingCompletion != null && pendingTransferId == transferId

    @Synchronized
    private fun isActiveDevice(deviceId: Long): Boolean =
        pendingCompletion != null && pendingDeviceId == deviceId

    @Synchronized
    private fun finish(result: Result<String>) {
        val callback = pendingCompletion ?: return
        val transferId = pendingTransferId ?: "none"
        result.fold(
            onSuccess = { SyncLog.i(TAG, "Sync finished: transfer=$transferId, result=$it") },
            onFailure = { SyncLog.w(TAG, "Sync failed: transfer=$transferId, error=${it.message}") },
        )
        pendingCompletion = null
        pendingRevision = 0L
        pendingTransferId = null
        pendingOperation = null
        pendingDeviceId = null
        cancelAllowed = false
        cancellationChanged(false)
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
        callback(result)
    }

    @Synchronized
    private fun armTimeout() {
        if (pendingCompletion == null) return
        val transferId = pendingTransferId ?: return
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        val runnable = Runnable {
            if (!isActive(transferId)) {
                SyncLog.i(TAG, "Ignoring timeout from inactive transfer=$transferId")
                return@Runnable
            }
            SyncLog.w(TAG, "Sync timeout: transfer=${pendingTransferId ?: "none"}, revision=$pendingRevision")
            val messageRes = if (pendingOperation == PendingOperation.CLEAR_WATCH) {
                R.string.watch_clear_timeout
            } else {
                R.string.sync_timeout
            }
            finish(Result.failure(syncFailure(messageRes)))
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, SYNC_TIMEOUT_MS)
    }

    @Synchronized
    override fun close() {
        SyncLog.i(TAG, "Closing Garmin sync manager")
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
        pendingCompletion = null
        pendingTransferId = null
        pendingOperation = null
        pendingDeviceId = null
        cancelAllowed = false
        runCatching { connectIq.unregisterForApplicationEvents() }
        runCatching { connectIq.shutdown(appContext) }
    }

    companion object {
        const val APP_UUID = "fa0bbecf-1e62-477b-b9cf-740aca2a4b32"
        private const val TAG = "TotpGarminSync"
        private const val SYNC_TIMEOUT_MS = 30_000L
        private fun normalizeMessageType(value: Any?): String? =
            value?.toString()?.removePrefix(":")
    }

    private fun syncFailure(messageRes: Int, vararg arguments: Any) =
        IllegalStateException(appContext.getString(messageRes, *arguments))

    private fun messageStatusText(status: ConnectIQ.IQMessageStatus): String =
        appContext.getString(messageStatusMessageRes(status))

    private fun connectionExceptionMessageRes(error: Throwable): Int = when (error) {
        is InvalidStateException -> R.string.sync_sdk_not_ready
        is ServiceUnavailableException -> R.string.sync_service_unavailable
        else -> R.string.sync_transport_unavailable
    }

}

internal fun messageStatusMessageRes(status: ConnectIQ.IQMessageStatus): Int = when (status) {
    ConnectIQ.IQMessageStatus.SUCCESS -> R.string.sync_status_success
    ConnectIQ.IQMessageStatus.FAILURE_UNKNOWN -> R.string.sync_status_unknown
    ConnectIQ.IQMessageStatus.FAILURE_INVALID_FORMAT -> R.string.sync_status_invalid_format
    ConnectIQ.IQMessageStatus.FAILURE_MESSAGE_TOO_LARGE -> R.string.sync_status_message_too_large
    ConnectIQ.IQMessageStatus.FAILURE_UNSUPPORTED_TYPE -> R.string.sync_status_unsupported_type
    ConnectIQ.IQMessageStatus.FAILURE_DURING_TRANSFER -> R.string.sync_status_during_transfer
    ConnectIQ.IQMessageStatus.FAILURE_INVALID_DEVICE -> R.string.sync_status_invalid_device
    ConnectIQ.IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> R.string.sync_status_device_not_connected
}

internal fun responseMatchesTransfer(responseTransferId: Any?, pendingTransferId: String?): Boolean =
    responseTransferId == null || responseTransferId.toString() == pendingTransferId

internal fun sdkErrorMessageRes(status: ConnectIQ.IQSdkErrorStatus): Int = when (status) {
    ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> R.string.sync_garmin_connect_missing
    ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> R.string.sync_garmin_connect_outdated
    ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> R.string.sync_service_unavailable
}

internal fun watchErrorMessageRes(error: String?): Int = when (error) {
    "Unsupported protocol", "Invalid message", "Invalid begin", "Invalid clear" -> R.string.sync_watch_incompatible
    "Stale revision" -> R.string.sync_watch_newer_revision
    "Wrong transfer", "Wrong sequence", "Incomplete snapshot" -> R.string.sync_watch_incomplete
    "Invalid record", "Unsupported TOTP" -> R.string.sync_watch_unsupported_entry
    "Checksum mismatch" -> R.string.sync_watch_checksum
    else -> R.string.sync_watch_rejected
}

internal fun watchErrorLogCategory(error: String?): String = when (error) {
    "Unsupported protocol", "Invalid message", "Invalid begin", "Invalid clear" -> "incompatible"
    "Stale revision" -> "stale_revision"
    "Wrong transfer", "Wrong sequence", "Incomplete snapshot" -> "incomplete"
    "Invalid record", "Unsupported TOTP" -> "unsupported_entry"
    "Checksum mismatch" -> "checksum"
    else -> "unknown"
}
