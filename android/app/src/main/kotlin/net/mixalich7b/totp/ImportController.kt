package net.mixalich7b.totp

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

internal class ImportController(
    private val activity: Activity,
    private val entryStore: LocalEntryStore,
    private val entryCollection: EntryCollection,
    private val screen: MainScreen,
    private val onError: ErrorHandler,
) : AutoCloseable {
    private var pendingImportEntries: List<TotpEntry>? = null
    private val migrationCollector = MigrationBatchCollector()

    @Volatile
    private var closed = false

    fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                if (closed) return@addOnSuccessListener
                barcode.rawValue?.let(::handleQr)
                    ?: toast(activity.getString(R.string.error_qr_empty))
            }
            .addOnCanceledListener {
                if (!closed) screen.showStatus(R.string.status_scan_canceled)
            }
            .addOnFailureListener { error ->
                if (!closed) {
                    onError(error) {
                        if (!closed) scanQr()
                    }
                }
            }
    }

    private fun handleQr(raw: String) {
        try {
            if (raw.startsWith("otpauth://", true)) {
                previewImport(
                    listOf(OtpAuthParser.parse(raw)),
                    R.string.dialog_import_totp,
                )
            } else if (raw.startsWith("otpauth-migration://", true)) {
                val batch = MigrationParser.parse(raw)
                when (val result = migrationCollector.add(batch)) {
                    is MigrationBatchResult.Complete -> handleMigrationResult(result)
                    is MigrationBatchResult.Pending -> {
                        screen.showStatus(
                            R.string.batch_status,
                            result.received,
                            result.total,
                        )
                    }
                }
            } else {
                error(activity.getString(R.string.error_not_totp_qr))
            }
        } catch (error: Exception) {
            onError(error, null)
        }
    }

    private fun handleMigrationResult(result: MigrationBatchResult.Complete) {
        if (result.issues.isEmpty()) {
            previewImport(result.entries, R.string.dialog_import_google_authenticator)
            return
        }

        clearPendingImport()
        pendingImportEntries = result.entries
        val counts = result.issues.groupingBy { it }.eachCount()
        val details = MigrationEntryIssue.entries.mapNotNull { issue ->
            counts[issue]?.let { count ->
                activity.getString(issue.messageRes(), count)
            }
        }.joinToString("\n")
        val message = activity.getString(
            R.string.import_issues_summary,
            result.issues.size,
            details,
        )
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_import_issues)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel) { _, _ -> clearPendingImport() }
            .setOnCancelListener { clearPendingImport() }

        if (result.entries.isEmpty()) {
            builder.setPositiveButton(android.R.string.ok) { _, _ -> clearPendingImport() }
        } else {
            builder.setPositiveButton(R.string.action_continue) { _, _ ->
                showImportPreview(
                    result.entries,
                    R.string.dialog_import_google_authenticator,
                )
            }
        }
        builder.show()
    }

    private fun MigrationEntryIssue.messageRes() = when (this) {
        MigrationEntryIssue.HOTP -> R.string.import_issue_hotp
        MigrationEntryIssue.UNSUPPORTED_ALGORITHM -> R.string.import_issue_algorithm
        MigrationEntryIssue.UNSUPPORTED_DIGITS -> R.string.import_issue_digits
        MigrationEntryIssue.MALFORMED -> R.string.import_issue_malformed
    }

    private fun previewImport(imported: List<TotpEntry>, titleRes: Int) {
        clearPendingImport()
        pendingImportEntries = imported
        showImportPreview(imported, titleRes)
    }

    private fun showImportPreview(imported: List<TotpEntry>, titleRes: Int) {
        val selected = BooleanArray(imported.size) { true }
        val previewItems = imported.map { entry ->
            activity.getString(
                R.string.import_preview_item,
                entry.displayName,
                entry.issuer.ifBlank {
                    activity.getString(R.string.value_not_set)
                },
                entry.accountName.ifBlank {
                    activity.getString(R.string.value_not_set)
                },
                entry.algorithm.name,
                entry.digits,
                entry.periodSeconds,
            )
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMultiChoiceItems(previewItems, selected) { _, index, checked ->
                selected[index] = checked
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> clearPendingImport() }
            .setPositiveButton(R.string.action_import) { _, _ ->
                val chosen = imported.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) {
                    screen.showStatus(R.string.import_nothing_selected)
                    clearPendingImport()
                } else {
                    val duplicateCount = ImportPlanner.duplicateCount(
                        entryCollection.entries,
                        chosen,
                    )
                    if (duplicateCount == 0) {
                        importSelected(chosen, ImportDuplicatePolicy.KEEP_BOTH)
                    } else {
                        showDuplicatePolicy(chosen, duplicateCount)
                    }
                }
            }
            .setOnCancelListener { clearPendingImport() }
            .show()
    }

    private fun showDuplicatePolicy(
        selected: List<TotpEntry>,
        duplicateCount: Int,
    ) {
        var policy = ImportDuplicatePolicy.SKIP
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_import_duplicates)
            .setMessage(
                activity.getString(
                    R.string.import_duplicates_found,
                    duplicateCount,
                ),
            )
            .setSingleChoiceItems(R.array.import_duplicate_policies, 0) { _, index ->
                policy = ImportDuplicatePolicy.entries[index]
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> clearPendingImport() }
            .setPositiveButton(R.string.action_continue) { _, _ ->
                importSelected(selected, policy)
            }
            .setOnCancelListener { clearPendingImport() }
            .show()
    }

    private fun importSelected(
        selected: List<TotpEntry>,
        policy: ImportDuplicatePolicy,
    ) {
        try {
            val plan = ImportPlanner.plan(entryCollection.entries, selected, policy)
            if (plan.entriesToWrite.isEmpty()) {
                screen.showStatus(
                    R.string.import_complete,
                    0,
                    plan.duplicateCount,
                )
                clearPendingImport()
                return
            }

            screen.setMutationControlsEnabled(false)
            entryStore.importEntries(
                entries = plan.entriesToWrite,
                onSuccess = {
                    entryCollection.upsertCopies(
                        plan.entriesToWrite,
                        System.currentTimeMillis(),
                    )
                    screen.refreshEntries()
                    screen.showStatus(
                        R.string.import_complete,
                        plan.entriesToWrite.size,
                        plan.duplicateCount,
                    )
                    screen.showDefaultEmptyText()
                    screen.setMutationControlsEnabled(true)
                    clearPendingImport()
                },
                onFailure = { error ->
                    screen.setMutationControlsEnabled(true)
                    if (error is StorageUnavailableException && error.canRecoverWithLocalReset) {
                        onError(error, null)
                        clearPendingImport()
                    } else {
                        onError(error) {
                            if (!closed) importSelected(selected, policy)
                        }
                    }
                },
                onRetry = ::showErrorStatus,
                onFinished = {},
            )
        } catch (error: Exception) {
            screen.setMutationControlsEnabled(true)
            onError(error, null)
            clearPendingImport()
        }
    }

    private fun clearPendingImport() {
        pendingImportEntries?.forEach { it.secret.fill(0) }
        pendingImportEntries = null
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private fun showErrorStatus(error: Throwable) {
        screen.showStatus(R.string.error_status, error.userMessage())
    }

    private fun Throwable.userMessage() = message ?: javaClass.simpleName

    override fun close() {
        closed = true
        clearPendingImport()
        migrationCollector.clear()
    }
}
