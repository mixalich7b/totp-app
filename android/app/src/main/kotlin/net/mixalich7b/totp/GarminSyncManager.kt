package net.mixalich7b.totp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.security.MessageDigest
import java.util.UUID

class GarminSyncManager(
    context: Context,
    private val statusChanged: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectIq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
    private val watchApp = IQApp(APP_UUID)
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var ready = false
    @Volatile
    private var pendingCompletion: ((Result<String>) -> Unit)? = null
    @Volatile
    private var pendingRevision = 0L
    @Volatile
    private var pendingTransferId: String? = null
    private var timeoutRunnable: Runnable? = null

    private val applicationListener = object : ConnectIQ.IQApplicationEventListener {
        override fun onMessageReceived(
            device: IQDevice,
            app: IQApp,
            messages: MutableList<Any>,
            status: ConnectIQ.IQMessageStatus,
        ) {
            Log.i(TAG, "Receive callback: status=$status, messages=${messages.size}, transfer=${pendingTransferId ?: "none"}")
            if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                Log.w(TAG, "Receive failed: status=$status, transfer=${pendingTransferId ?: "none"}")
                finish(Result.failure(IllegalStateException(appContext.getString(R.string.sync_receive_failed, status))))
                return
            }
            messages.filterIsInstance<Map<*, *>>().forEach { message ->
                val type = normalizeMessageType(message["t"])
                Log.i(TAG, "Received message: type=${type ?: "missing"}, transfer=${pendingTransferId ?: "none"}")
                when (type) {
                    "a" -> {
                        val revision = (message["r"] as? Number)?.toLong() ?: return@forEach
                        if (revision == pendingRevision) {
                            Log.i(TAG, "ACK accepted: revision=$revision, transfer=${pendingTransferId ?: "none"}")
                            finish(Result.success("Синхронизировано, revision $revision"))
                        } else {
                            Log.w(TAG, "ACK ignored: revision=$revision, expected=$pendingRevision, transfer=${pendingTransferId ?: "none"}")
                        }
                    }
                    "e" -> {
                        val errorMessage = message["m"]?.toString() ?: "Ошибка часов"
                        Log.w(TAG, "Watch rejected transfer=${pendingTransferId ?: "none"}: $errorMessage")
                        finish(Result.failure(IllegalStateException(errorMessage)))
                    }
                    else -> Log.w(TAG, "Ignoring unsupported response type=${type ?: "missing"}, transfer=${pendingTransferId ?: "none"}")
                }
            }
        }
    }

    init {
        connectIq.initialize(appContext, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                ready = true
                Log.i(TAG, "Connect IQ SDK ready")
                statusChanged(appContext.getString(R.string.sync_sdk_ready))
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                Log.e(TAG, "Connect IQ SDK initialization failed: $status")
                statusChanged(appContext.getString(R.string.sync_sdk_unavailable, status))
            }

            override fun onSdkShutDown() {
                ready = false
                Log.i(TAG, "Connect IQ SDK shut down")
                statusChanged(appContext.getString(R.string.sync_sdk_shutdown))
            }
        })
    }

    fun sync(entries: List<TotpEntry>, revision: Long, completion: (Result<String>) -> Unit) {
        Log.i(TAG, "Sync requested: revision=$revision, entries=${entries.size}")
        if (!ready) {
            Log.w(TAG, "Sync rejected: Connect IQ SDK is not ready")
            completion(Result.failure(IllegalStateException(appContext.getString(R.string.sync_sdk_not_ready))))
            return
        }
        val device = runCatching { connectIq.connectedDevices.firstOrNull() }.getOrElse {
            Log.e(TAG, "Failed to query connected devices", it)
            completion(Result.failure(it)); return
        }
        if (device == null) {
            Log.w(TAG, "Sync rejected: no connected Garmin device")
            completion(Result.failure(IllegalStateException(appContext.getString(R.string.sync_no_device))))
            return
        }
        if (pendingCompletion != null) {
            Log.w(TAG, "Sync rejected: another transfer is active (${pendingTransferId ?: "unknown"})")
            completion(Result.failure(IllegalStateException(appContext.getString(R.string.sync_already_running))))
            return
        }

        try {
            connectIq.registerForAppEvents(device, watchApp, applicationListener)
            Log.i(TAG, "Registered application event listener")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to register application event listener", error)
            completion(Result.failure(error))
            return
        }

        pendingCompletion = completion
        pendingRevision = revision
        val transferId = UUID.randomUUID().toString().take(12)
        pendingTransferId = transferId
        armTimeout()
        val messages = mutableListOf<Map<String, Any>>()
        messages += mapOf(
            "t" to "b", "v" to 1, "x" to transferId, "r" to revision,
            "n" to entries.size, "h" to snapshotHash(entries)
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
        Log.i(TAG, "Transfer prepared: transfer=$transferId, revision=$revision, entries=${entries.size}, messages=${messages.size}")
        statusChanged(appContext.getString(R.string.sync_transfering_entries, entries.size))
        sendNext(device, messages, 0)
    }

    private fun sendNext(device: IQDevice, messages: List<Map<String, Any>>, index: Int) {
        if (pendingCompletion == null) return
        if (index >= messages.size) {
            Log.i(TAG, "All messages sent; waiting for ACK: transfer=${pendingTransferId ?: "none"}, revision=$pendingRevision")
            statusChanged(appContext.getString(R.string.sync_waiting_for_ack))
            armTimeout()
            return
        }
        val type = normalizeMessageType(messages[index]["t"]) ?: "missing"
        Log.i(TAG, "Sending message: type=$type, position=${index + 1}/${messages.size}, transfer=${pendingTransferId ?: "none"}")
        try {
            connectIq.sendMessage(device, watchApp, messages[index], object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, app: IQApp, status: ConnectIQ.IQMessageStatus) {
                    if (pendingCompletion == null) return
                    Log.i(TAG, "Send callback: type=$type, position=${index + 1}/${messages.size}, status=$status, transfer=${pendingTransferId ?: "none"}")
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        armTimeout()
                        sendNext(device, messages, index + 1)
                    } else {
                        Log.w(TAG, "Transfer failed while sending type=$type: status=$status, transfer=${pendingTransferId ?: "none"}")
                        finish(Result.failure(IllegalStateException(appContext.getString(R.string.sync_transfer_failed, status))))
                    }
                }
            })
        } catch (error: Exception) {
            Log.e(TAG, "Exception while sending type=$type, transfer=${pendingTransferId ?: "none"}", error)
            finish(Result.failure(error))
        }
    }

    private fun snapshotHash(entries: List<TotpEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            listOf(entry.id, entry.displayName, entry.algorithm.wireValue.toString(), entry.digits.toString(), entry.periodSeconds.toString())
                .forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
            digest.update(entry.secret)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    private fun finish(result: Result<String>) {
        val callback = pendingCompletion ?: return
        val transferId = pendingTransferId ?: "none"
        result.fold(
            onSuccess = { Log.i(TAG, "Sync finished: transfer=$transferId, result=$it") },
            onFailure = { Log.w(TAG, "Sync failed: transfer=$transferId, error=${it.message}") },
        )
        pendingCompletion = null
        pendingRevision = 0L
        pendingTransferId = null
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
        callback(result)
    }

    private fun armTimeout() {
        if (pendingCompletion == null) return
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        val runnable = Runnable {
            Log.w(TAG, "Sync timeout: transfer=${pendingTransferId ?: "none"}, revision=$pendingRevision")
            finish(Result.failure(IllegalStateException(appContext.getString(R.string.sync_timeout))))
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, SYNC_TIMEOUT_MS)
    }

    override fun close() {
        Log.i(TAG, "Closing Garmin sync manager")
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
        pendingCompletion = null
        pendingTransferId = null
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
}
