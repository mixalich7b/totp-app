package net.mixalich7b.totp

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner

internal fun EditText.configureSecretInput() {
    inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    isSaveEnabled = false
}

internal class EntryEditorController(
    private val activity: Activity,
    private val entryStore: LocalEntryStore,
    private val entryCollection: EntryCollection,
    private val screen: MainScreen,
    private val onError: ErrorHandler,
) {
    private val dialogHorizontalPaddingPx =
        dimenPx(R.dimen.totp_dialog_horizontal_padding)
    private val dialogVerticalPaddingPx =
        dimenPx(R.dimen.totp_dialog_vertical_padding)
    private val dialogFieldGapPx =
        dimenPx(R.dimen.totp_dialog_field_gap)

    fun showAddDialog() {
        showEntryDialog(existingEntry = null)
    }

    fun showEditDialog(entry: TotpEntry) {
        showEntryDialog(existingEntry = entry)
    }

    private fun showEntryDialog(existingEntry: TotpEntry?) {
        val name = field(R.string.field_name)
        val issuer = field(R.string.field_issuer_optional)
        val account = field(R.string.field_account_optional)
        val secret = if (existingEntry == null) {
            field(R.string.field_base32_secret).apply { configureSecretInput() }
        } else {
            null
        }
        val algorithm = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                activity.resources.getStringArray(R.array.totp_algorithms),
            )
        }
        val digits = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                activity.resources.getStringArray(R.array.totp_digits),
            )
        }
        val period = field(R.string.field_period_seconds).apply {
            setText(R.string.default_period)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        existingEntry?.let { entry ->
            name.setText(entry.displayName)
            issuer.setText(entry.issuer)
            account.setText(entry.accountName)
            algorithm.setSelection(entry.algorithm.ordinal)
            digits.setSelection(if (entry.digits == 6) 0 else 1)
            period.setText(entry.periodSeconds.toString())
        }
        val fields = listOfNotNull(name, issuer, account, secret, algorithm, digits, period)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dialogHorizontalPaddingPx,
                dialogVerticalPaddingPx,
                dialogHorizontalPaddingPx,
                dialogVerticalPaddingPx,
            )
            fields.forEachIndexed { index, view ->
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index != fields.lastIndex) bottomMargin = dialogFieldGapPx
                }
                addView(view, params)
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(
                if (existingEntry == null) {
                    R.string.dialog_new_totp
                } else {
                    R.string.dialog_edit_totp
                },
            )
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        var submit: (() -> Unit)? = null
        dialog.setOnShowListener {
            submit = {
                try {
                    val displayName = name.text.toString().trim()
                    val issuerValue = issuer.text.toString().trim()
                    val accountValue = account.text.toString().trim()
                    val algorithmValue =
                        TotpAlgorithm.fromName(algorithm.selectedItem.toString())
                    val digitsValue = if (digits.selectedItemPosition == 0) 6 else 8
                    val periodValue = period.text.toString().toInt()
                    val entry = if (existingEntry == null) {
                        TotpEntry(
                            displayName = displayName,
                            issuer = issuerValue,
                            accountName = accountValue,
                            secret = Base32.decode(checkNotNull(secret).text.toString()),
                            algorithm = algorithmValue,
                            digits = digitsValue,
                            periodSeconds = periodValue,
                        )
                    } else {
                        existingEntry.copy(
                            displayName = displayName,
                            issuer = issuerValue,
                            accountName = accountValue,
                            algorithm = algorithmValue,
                            digits = digitsValue,
                            periodSeconds = periodValue,
                        )
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    screen.setMutationControlsEnabled(false)
                    if (existingEntry == null) {
                        entryStore.add(
                            entry = entry,
                            onSuccess = { revision ->
                                dialog.dismiss()
                                entryCollection.addCopy(entry, System.currentTimeMillis())
                                screen.refreshEntries()
                                screen.showLocalStatus(revision, entryCollection.size)
                                screen.setMutationControlsEnabled(true)
                            },
                            onFailure = { error ->
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                screen.setMutationControlsEnabled(true)
                                onError(error) {
                                    submit?.invoke()
                                }
                            },
                            onRetry = ::showErrorStatus,
                            onFinished = { entry.secret.fill(0) },
                        )
                    } else {
                        entryStore.update(
                            entry = entry,
                            onSuccess = { revision ->
                                dialog.dismiss()
                                entryCollection.updateCopy(entry, System.currentTimeMillis())
                                screen.refreshEntries()
                                screen.showLocalStatus(revision, entryCollection.size)
                                screen.setMutationControlsEnabled(true)
                            },
                            onFailure = { error ->
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                screen.setMutationControlsEnabled(true)
                                onError(error) {
                                    submit?.invoke()
                                }
                            },
                            onRetry = ::showErrorStatus,
                        )
                    }
                } catch (error: Exception) {
                    screen.setMutationControlsEnabled(true)
                    onError(error, null)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit?.invoke()
            }
        }
        dialog.setOnDismissListener {
            secret?.text?.clear()
        }
        dialog.show()
    }

    fun confirmDelete(entry: TotpEntry) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_delete_record)
            .setMessage(entry.displayName)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleteEntry(entry)
            }
            .show()
    }

    private fun deleteEntry(entry: TotpEntry) {
        screen.setMutationControlsEnabled(false)
        entryStore.delete(
            id = entry.id,
            onSuccess = { revision ->
                entryCollection.remove(entry.id)
                screen.refreshEntries()
                screen.showLocalStatus(revision, entryCollection.size)
                screen.setMutationControlsEnabled(true)
            },
            onFailure = { error ->
                screen.setMutationControlsEnabled(true)
                onError(error) {
                    deleteEntry(entry)
                }
            },
            onRetry = ::showErrorStatus,
        )
    }

    private fun showErrorStatus(error: Throwable) {
        screen.showStatus(R.string.error_status, error.userMessage())
    }

    private fun Throwable.userMessage() = message ?: javaClass.simpleName

    private fun field(hintResId: Int) = EditText(activity).apply {
        hint = activity.getString(hintResId)
        setSingleLine(true)
    }

    private fun dimenPx(resId: Int) = activity.resources.getDimensionPixelSize(resId)
}
