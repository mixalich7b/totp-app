package net.mixalich7b.totp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

internal data class LocalSnapshot(
    val entries: List<TotpEntry>,
    val revision: Long,
)

internal class LocalEntryStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var repository = SecureEntryRepository(appContext)

    @Volatile
    private var closed = false

    fun load(
        onSuccess: (LocalSnapshot) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
    ) {
        snapshot(onSuccess, onFailure, onRetry)
    }

    fun snapshot(
        onSuccess: (LocalSnapshot) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
    ) {
        execute(
            operation = {
                LocalSnapshot(
                    entries = repository.list(),
                    revision = repository.revision(),
                )
            },
            onSuccess = onSuccess,
            onFailure = onFailure,
            onRetry = onRetry,
            onDiscard = ::clearSnapshot,
        )
    }

    fun add(
        entry: TotpEntry,
        onSuccess: (Long) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
        onFinished: () -> Unit,
    ) {
        execute(
            operation = { repository.add(listOf(entry)) },
            onSuccess = onSuccess,
            onFailure = onFailure,
            onRetry = onRetry,
            onFinished = onFinished,
        )
    }

    fun importEntries(
        entries: List<TotpEntry>,
        onSuccess: (Long) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
        onFinished: () -> Unit,
    ) {
        execute(
            operation = { repository.importEntries(entries) },
            onSuccess = onSuccess,
            onFailure = onFailure,
            onRetry = onRetry,
            onFinished = onFinished,
        )
    }

    fun update(
        entry: TotpEntry,
        onSuccess: (Long) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
    ) {
        execute(
            operation = { repository.update(entry) },
            onSuccess = onSuccess,
            onFailure = onFailure,
            onRetry = onRetry,
        )
    }

    fun delete(
        id: String,
        onSuccess: (Long) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
    ) {
        execute(
            operation = { repository.delete(id) },
            onSuccess = onSuccess,
            onFailure = onFailure,
            onRetry = onRetry,
        )
    }

    fun reset(
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        execute(
            operation = {
                try {
                    repository.close()
                    SecureEntryRepository.resetLocalStorage(appContext)
                    repository = SecureEntryRepository(appContext)
                } catch (error: Exception) {
                    runCatching { repository = SecureEntryRepository(appContext) }
                    throw error
                }
            },
            onSuccess = { onSuccess() },
            onFailure = onFailure,
        )
    }

    private fun clearSnapshot(snapshot: LocalSnapshot) {
        snapshot.entries.forEach { it.secret.fill(0) }
    }

    @Synchronized
    private fun <T> execute(
        operation: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRetry: (Throwable) -> Unit = {},
        onFinished: () -> Unit = {},
        onDiscard: (T) -> Unit = {},
    ) {
        if (closed) {
            onFinished()
            return
        }
        executor.execute {
            val result = runWithStorageResetRetries(operation, onRetry)
            if (closed) {
                result.getOrNull()?.let(onDiscard)
                onFinished()
                return@execute
            }
            mainHandler.post {
                if (closed) {
                    result.getOrNull()?.let(onDiscard)
                    onFinished()
                    return@post
                }
                try {
                    result.fold(onSuccess, onFailure)
                } finally {
                    onFinished()
                }
            }
        }
    }

    private fun <T> runWithStorageResetRetries(
        operation: () -> T,
        onRetry: (Throwable) -> Unit,
    ): Result<T> {
        STORAGE_RESET_RETRY_DELAYS_MS.forEach { delayMs ->
            val result = runCatching(operation)
            val error = result.exceptionOrNull()
            if (error == null || !error.shouldRetryBeforeLocalReset() || closed) return result
            postRetry(error, onRetry)
            if (!sleepBeforeRetry(delayMs)) return result
        }
        return runCatching(operation)
    }

    private fun Throwable.shouldRetryBeforeLocalReset(): Boolean =
        this is StorageUnavailableException && canRecoverWithLocalReset

    private fun postRetry(error: Throwable, onRetry: (Throwable) -> Unit) {
        mainHandler.post {
            if (!closed) onRetry(error)
        }
    }

    private fun sleepBeforeRetry(delayMs: Long): Boolean = try {
        Thread.sleep(delayMs)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        executor.execute { repository.close() }
        executor.shutdown()
    }

    companion object {
        private val STORAGE_RESET_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)
    }
}
