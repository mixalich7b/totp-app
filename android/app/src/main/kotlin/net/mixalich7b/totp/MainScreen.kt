package net.mixalich7b.totp

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal data class MainScreenActions(
    val addEntry: () -> Unit,
    val scanQr: () -> Unit,
    val synchronize: () -> Unit,
    val clearWatch: () -> Unit,
    val deleteEntry: (TotpEntry) -> Unit,
)

internal class MainScreen(
    private val activity: Activity,
    private val entries: List<TotpEntry>,
    private val actions: MainScreenActions,
) {
    private val edgePaddingPx = dimenPx(R.dimen.totp_screen_edge_padding)
    private val contentGapPx = dimenPx(R.dimen.totp_content_gap)
    private val buttonGapPx = dimenPx(R.dimen.totp_button_gap)
    private val bottomPaddingPx = dimenPx(R.dimen.totp_screen_bottom_padding)
    private val rowTextSizePx = dimen(R.dimen.totp_row_text_size)
    private val rowPaddingHorizontalPx = dimenPx(R.dimen.totp_row_padding_horizontal)
    private val rowPaddingVerticalPx = dimenPx(R.dimen.totp_row_padding_vertical)

    private val adapter = EntryAdapter()
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var syncButton: Button
    private lateinit var addButton: Button
    private lateinit var qrButton: Button
    private lateinit var clearWatchButton: Button
    private lateinit var entriesList: ListView

    val contentView: View = buildContent()

    fun showLoading() {
        statusView.setText(R.string.status_loading_local_db)
        emptyView.setText(R.string.status_loading_local_db)
        refreshEntries()
    }

    fun showStatus(text: CharSequence) {
        statusView.text = text
    }

    fun showStatus(resId: Int, vararg formatArgs: Any) {
        statusView.text = activity.getString(resId, *formatArgs)
    }

    fun showLocalStatus(revision: Long, entryCount: Int) {
        showStatus(R.string.local_status, revision, entryCount)
        showDefaultEmptyText()
    }

    fun showDefaultEmptyText() {
        emptyView.setText(R.string.empty_secrets)
    }

    fun refreshEntries() {
        adapter.notifyDataSetChanged()
    }

    fun setMutationControlsEnabled(enabled: Boolean) {
        addButton.isEnabled = enabled
        qrButton.isEnabled = enabled
        clearWatchButton.isEnabled = enabled
        entriesList.isEnabled = enabled
    }

    fun setSyncButton(textResId: Int, enabled: Boolean) {
        syncButton.setText(textResId)
        syncButton.isEnabled = enabled
    }

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.app_surface))
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

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = activity.getString(R.string.app_name)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.totp_title_text_size),
            )
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearWatchButton = Button(activity).apply {
            setText(R.string.action_clear_watch)
            contentDescription = activity.getString(R.string.dialog_clear_watch)
            setOnClickListener { actions.clearWatch() }
        }
        titleRow.addView(
            clearWatchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            titleRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = contentGapPx
            },
        )

        statusView = TextView(activity).apply {
            setText(R.string.status_initializing)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.totp_status_text_size),
            )
            setPadding(0, 0, 0, contentGapPx)
        }
        root.addView(statusView)

        entriesList = ListView(activity).apply {
            adapter = this@MainScreen.adapter
            emptyView = TextView(activity).apply {
                setText(R.string.empty_secrets)
                gravity = Gravity.CENTER
            }
            setOnItemClickListener { _, _, position, _ ->
                entries.getOrNull(position)?.let(actions.deleteEntry)
            }
        }
        emptyView = entriesList.emptyView as TextView
        root.addView(
            emptyView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            entriesList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        addButton = Button(activity).apply {
            setText(R.string.action_add)
            setOnClickListener { actions.addEntry() }
        }
        buttons.addView(addButton, weighted())
        qrButton = Button(activity).apply {
            setText(R.string.qr)
            setOnClickListener { actions.scanQr() }
        }
        buttons.addView(qrButton, weighted())
        syncButton = Button(activity).apply {
            setText(R.string.action_sync)
            setOnClickListener { actions.synchronize() }
        }
        buttons.addView(syncButton, weighted())
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = buttonGapPx
            },
        )
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun weighted() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dimenPx(resId: Int) = activity.resources.getDimensionPixelSize(resId)

    private fun dimen(resId: Int) = activity.resources.getDimension(resId)

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, rowTextSizePx)
                setPadding(
                    rowPaddingHorizontalPx,
                    rowPaddingVerticalPx,
                    rowPaddingHorizontalPx,
                    rowPaddingVerticalPx,
                )
            }
            val entry = getItem(position)
            val subtitle = listOf(entry.issuer, entry.accountName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            view.text = if (subtitle.isBlank()) {
                activity.getString(R.string.entry_line_single, entry.displayName)
            } else {
                activity.getString(
                    R.string.entry_line_with_subtitle,
                    entry.displayName,
                    subtitle,
                )
            }
            return view
        }
    }
}
