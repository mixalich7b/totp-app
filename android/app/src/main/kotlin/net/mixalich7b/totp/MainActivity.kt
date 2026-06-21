package net.mixalich7b.totp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : Activity() {
    private lateinit var repository: SecureEntryRepository
    private lateinit var syncManager: GarminSyncManager
    private lateinit var adapter: EntryAdapter
    private lateinit var statusView: TextView
    private var entries = mutableListOf<TotpEntry>()
    private val migrationCollector = MigrationBatchCollector()
    private val edgePaddingPx by lazy { dimenPx(R.dimen.totp_screen_edge_padding) }
    private val contentGapPx by lazy { dimenPx(R.dimen.totp_content_gap) }
    private val buttonGapPx by lazy { dimenPx(R.dimen.totp_button_gap) }
    private val bottomPaddingPx by lazy { dimenPx(R.dimen.totp_screen_bottom_padding) }
    private val dialogHorizontalPaddingPx by lazy { dimenPx(R.dimen.totp_dialog_horizontal_padding) }
    private val dialogVerticalPaddingPx by lazy { dimenPx(R.dimen.totp_dialog_vertical_padding) }
    private val dialogFieldGapPx by lazy { dimenPx(R.dimen.totp_dialog_field_gap) }
    private val titleTextSizePx by lazy { dimen(R.dimen.totp_title_text_size) }
    private val statusTextSizePx by lazy { dimen(R.dimen.totp_status_text_size) }
    private val rowTextSizePx by lazy { dimen(R.dimen.totp_row_text_size) }
    private val rowPaddingHorizontalPx by lazy { dimenPx(R.dimen.totp_row_padding_horizontal) }
    private val rowPaddingVerticalPx by lazy { dimenPx(R.dimen.totp_row_padding_vertical) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowSurfaceColors()
        title = getString(R.string.app_name)
        repository = SecureEntryRepository(this)
        setContentView(buildContent())
        syncManager = GarminSyncManager(this) { text -> runOnUiThread { statusView.text = text } }
        reload()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.app_surface))
            setPadding(edgePaddingPx, edgePaddingPx, edgePaddingPx, bottomPaddingPx)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                edgePaddingPx + systemBars.left,
                edgePaddingPx + systemBars.top,
                edgePaddingPx + systemBars.right,
                bottomPaddingPx + systemBars.bottom,
            )
            insets
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = titleTextSizePx
            setPadding(0, 0, 0, contentGapPx)
        })
        statusView = TextView(this).apply {
            text = getString(R.string.status_initializing)
            textSize = statusTextSizePx
            setPadding(0, 0, 0, contentGapPx)
        }
        root.addView(statusView)

        adapter = EntryAdapter()
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            emptyView = TextView(this@MainActivity).apply {
                text = getString(R.string.empty_secrets)
                gravity = Gravity.CENTER
            }
            setOnItemClickListener { _, _, position, _ -> confirmDelete(entries[position]) }
        }
        root.addView(list.emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = getString(R.string.action_add)
            setOnClickListener { showAddDialog() }
        }, weighted())
        buttons.addView(Button(this).apply {
            text = getString(R.string.qr)
            setOnClickListener { scanQr() }
        }, weighted())
        buttons.addView(Button(this).apply {
            text = getString(R.string.action_sync)
            setOnClickListener { synchronize() }
        }, weighted())
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = buttonGapPx
        })
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun applyWindowSurfaceColors() {
        val surfaceColor = ContextCompat.getColor(this, R.color.app_surface)
        window.statusBarColor = surfaceColor
        window.navigationBarColor = surfaceColor
        window.decorView.setBackgroundColor(surfaceColor)
    }

    private fun reload() {
        entries.forEach { it.secret.fill(0) }
        try {
            entries = repository.list().toMutableList()
            adapter.notifyDataSetChanged()
            statusView.text = getString(R.string.local_status, repository.revision(), entries.size)
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun showAddDialog() {
        val name = field(R.string.field_name)
        val issuer = field(R.string.field_issuer_optional)
        val account = field(R.string.field_account_optional)
        val secret = field(R.string.field_base32_secret).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val algorithm = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.totp_algorithms)
            )
        }
        val digits = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.totp_digits)
            )
        }
        val period = field(R.string.field_period_seconds).apply {
            setText(R.string.default_period)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialogHorizontalPaddingPx, dialogVerticalPaddingPx, dialogHorizontalPaddingPx, dialogVerticalPaddingPx)
            listOf(name, issuer, account, secret, algorithm, digits, period).forEachIndexed { index, view ->
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index != 6) bottomMargin = dialogFieldGapPx
                }
                addView(view, params)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_totp)
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val entry = TotpEntry(
                        displayName = name.text.toString().trim(),
                        issuer = issuer.text.toString().trim(),
                        accountName = account.text.toString().trim(),
                        secret = Base32.decode(secret.text.toString()),
                        algorithm = TotpAlgorithm.fromName(algorithm.selectedItem.toString()),
                        digits = if (digits.selectedItemPosition == 0) 6 else 8,
                        periodSeconds = period.text.toString().toInt(),
                    )
                    repository.add(listOf(entry))
                    dialog.dismiss()
                    reload()
                } catch (error: Exception) {
                    toast(error.userMessage())
                }
            }
        }
        dialog.show()
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(::handleQr) ?: toast(getString(R.string.error_qr_empty)) }
            .addOnCanceledListener { statusView.text = getString(R.string.status_scan_canceled) }
            .addOnFailureListener(::showError)
    }

    private fun handleQr(raw: String) {
        try {
            if (raw.startsWith("otpauth://", true)) {
                previewImport(listOf(OtpAuthParser.parse(raw)), R.string.dialog_import_totp)
            } else if (raw.startsWith("otpauth-migration://", true)) {
                val batch = MigrationParser.parse(raw)
                when (val result = migrationCollector.add(batch)) {
                    is MigrationBatchResult.Complete -> previewImport(
                        result.entries,
                        R.string.dialog_import_google_authenticator,
                    )
                    is MigrationBatchResult.Pending -> {
                        statusView.text = getString(R.string.batch_status, result.received, result.total)
                    }
                }
            } else {
                error(getString(R.string.error_not_totp_qr))
            }
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun previewImport(imported: List<TotpEntry>, titleRes: Int) {
        val names = imported.joinToString("\n") { getString(R.string.import_item_bullet, it.displayName) }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(names)
            .setNegativeButton(R.string.action_cancel) { _, _ -> imported.forEach { it.secret.fill(0) } }
            .setPositiveButton(R.string.action_import) { _, _ ->
                try {
                    repository.add(imported)
                    reload()
                } catch (error: Exception) {
                    showError(error)
                }
            }
            .show()
    }

    private fun confirmDelete(entry: TotpEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_record)
            .setMessage(entry.displayName)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.delete(entry.id)
                reload()
            }
            .show()
    }

    private fun synchronize() {
        syncManager.sync(entries, repository.revision()) { result ->
            runOnUiThread {
                result.onSuccess { statusView.text = it }.onFailure(::showError)
            }
        }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
    }

    private fun field(hintResId: Int) = EditText(this).apply {
        hint = getString(hintResId)
        setSingleLine(true)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dimenPx(resId: Int) = resources.getDimensionPixelSize(resId)
    private fun dimen(resId: Int) = resources.getDimension(resId)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun showError(error: Throwable) {
        statusView.text = getString(R.string.error_status, error.userMessage())
        toast(error.userMessage())
    }
    private fun Throwable.userMessage() = message ?: javaClass.simpleName

    override fun onDestroy() {
        entries.forEach { it.secret.fill(0) }
        migrationCollector.clear()
        syncManager.close()
        repository.close()
        super.onDestroy()
    }

    private inner class EntryAdapter : android.widget.BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = rowTextSizePx
                setPadding(rowPaddingHorizontalPx, rowPaddingVerticalPx, rowPaddingHorizontalPx, rowPaddingVerticalPx)
            }
            val entry = getItem(position)
            val subtitle = listOf(entry.issuer, entry.accountName).filter { it.isNotBlank() }.joinToString(" · ")
            view.text = if (subtitle.isBlank()) {
                getString(R.string.entry_line_single, entry.displayName)
            } else {
                getString(R.string.entry_line_with_subtitle, entry.displayName, subtitle)
            }
            return view
        }
    }
}
