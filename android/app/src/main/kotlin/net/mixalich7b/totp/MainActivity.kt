package net.mixalich7b.totp

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class MainActivity : Activity() {
    private lateinit var entryStore: LocalEntryStore
    private lateinit var entryEditorController: EntryEditorController
    private lateinit var importController: ImportController
    private lateinit var syncController: SyncController
    private lateinit var screen: MainScreen
    private val entryCollection = EntryCollection()
    private var storageResetDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowSurfaceColors()
        title = getString(R.string.app_name)
        entryStore = LocalEntryStore(this)
        screen = MainScreen(
            activity = this,
            entries = entryCollection.entries,
            actions = MainScreenActions(
                addEntry = { entryEditorController.showAddDialog() },
                scanQr = { importController.scanQr() },
                synchronize = { syncController.onSyncPressed() },
                clearWatch = { syncController.confirmClearWatch() },
                deleteEntry = { entryEditorController.confirmDelete(it) },
            ),
        )
        setContentView(screen.contentView)
        entryEditorController = EntryEditorController(
            activity = this,
            entryStore = entryStore,
            entryCollection = entryCollection,
            screen = screen,
            onError = ::handleError,
        )
        importController = ImportController(
            activity = this,
            entryStore = entryStore,
            entryCollection = entryCollection,
            screen = screen,
            onError = ::handleError,
        )
        syncController = SyncController(
            activity = this,
            entryStore = entryStore,
            screen = screen,
            onStorageError = ::handleError,
            onSyncError = ::showError,
        )
        loadEntries()
    }

    @Suppress("DEPRECATION")
    private fun applyWindowSurfaceColors() {
        val surfaceColor = ContextCompat.getColor(this, R.color.app_surface)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = surfaceColor
            window.navigationBarColor = surfaceColor
        }
        window.decorView.setBackgroundColor(surfaceColor)
    }

    private fun loadEntries() {
        entryCollection.clear()
        screen.showLoading()
        entryStore.load(
            onSuccess = { snapshot ->
                entryCollection.replaceAll(snapshot.entries)
                screen.refreshEntries()
                screen.showLocalStatus(snapshot.revision, entryCollection.size)
            },
            onFailure = { error ->
                screen.showDefaultEmptyText()
                handleError(error)
            },
        )
    }

    private fun showStorageResetDialog(error: StorageUnavailableException) {
        screen.showStatus(R.string.error_status, error.userMessage())
        if (storageResetDialogVisible || isFinishing || isDestroyed) return
        storageResetDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_storage_unavailable)
            .setMessage(R.string.message_storage_unavailable)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset_storage) { _, _ -> resetLocalStorage() }
            .setOnDismissListener { storageResetDialogVisible = false }
            .show()
    }

    private fun resetLocalStorage() {
        entryCollection.clear()
        screen.refreshEntries()
        entryStore.reset(
            onSuccess = {
                loadEntries()
                toast(getString(R.string.storage_reset_complete))
            },
            onFailure = { error ->
                showError(error)
            },
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun handleError(error: Throwable) {
        if (error is StorageUnavailableException) {
            showStorageResetDialog(error)
        } else {
            showError(error)
        }
    }
    private fun showError(error: Throwable) {
        screen.showStatus(R.string.error_status, error.userMessage())
        toast(error.userMessage())
    }
    private fun Throwable.userMessage() = message ?: javaClass.simpleName

    override fun onDestroy() {
        entryCollection.clear()
        importController.close()
        syncController.close()
        entryStore.close()
        super.onDestroy()
    }
}
