package net.mixalich7b.totp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal data class MainScreenActions(
    val addEntry: () -> Unit,
    val scanQr: () -> Unit,
    val synchronize: () -> Unit,
    val clearWatch: () -> Unit,
    val deleteEntry: (TotpEntry) -> Unit,
    val editEntry: (TotpEntry) -> Unit,
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
    private val rowSubtitleTextSizePx = dimen(R.dimen.totp_row_subtitle_text_size)
    private val rowCodeTextSizePx = dimen(R.dimen.totp_row_code_text_size)
    private val rowPaddingHorizontalPx = dimenPx(R.dimen.totp_row_padding_horizontal)
    private val rowPaddingVerticalPx = dimenPx(R.dimen.totp_row_padding_vertical)
    private val rowNameCodeGapPx = dimenPx(R.dimen.totp_row_name_code_gap)
    private val rowMinHeightPx = dimenPx(R.dimen.totp_row_min_height)
    private val rowTimerHeightPx = dimenPx(R.dimen.totp_row_timer_height)
    private val rowActionWidthPx = dimenPx(R.dimen.totp_row_action_width)
    private val rowActionIconSizePx = dimenPx(R.dimen.totp_row_action_icon_size)
    private val rowActionIconStrokePx = dimenPx(R.dimen.totp_row_action_icon_stroke)
    private val swipeTouchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private val adapter = EntryAdapter()
    private val codeTicker = object : Runnable {
        override fun run() {
            if (!codeTickerRunning) return
            updateVisibleCodes()
            scheduleNextCodeTick()
        }
    }
    private var openEntryId: String? = null
    private var openRow: SwipeRow? = null
    private var codeTickerRunning = false
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
        closeSwipeActions(animated = false)
        adapter.notifyDataSetChanged()
        if (::entriesList.isInitialized) {
            entriesList.post { updateVisibleCodes() }
        }
    }

    fun setMutationControlsEnabled(enabled: Boolean) {
        if (!enabled) closeSwipeActions()
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
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                startCodeTicker()
            }

            override fun onViewDetachedFromWindow(view: View) {
                stopCodeTicker()
            }
        })
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

    private fun startCodeTicker() {
        if (codeTickerRunning) return
        codeTickerRunning = true
        updateVisibleCodes()
        scheduleNextCodeTick()
    }

    private fun stopCodeTicker() {
        codeTickerRunning = false
        if (::entriesList.isInitialized) {
            entriesList.removeCallbacks(codeTicker)
        }
    }

    private fun scheduleNextCodeTick() {
        if (!codeTickerRunning || !::entriesList.isInitialized) return
        entriesList.removeCallbacks(codeTicker)
        val now = System.currentTimeMillis()
        val delayMs = (TICKER_INTERVAL_MS - (now % TICKER_INTERVAL_MS))
            .coerceIn(1L, TICKER_INTERVAL_MS)
        entriesList.postDelayed(codeTicker, delayMs)
    }

    private fun updateVisibleCodes(nowMillis: Long = System.currentTimeMillis()) {
        if (!::entriesList.isInitialized) return
        for (index in 0 until entriesList.childCount) {
            (entriesList.getChildAt(index).tag as? SwipeRow)?.updateTotp(nowMillis)
        }
    }

    private fun copyCurrentTotp(entry: TotpEntry) {
        val code = Totp.generate(entry)
        val clipboard = ContextCompat.getSystemService(activity, ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                activity.getString(R.string.totp_code_clip_label),
                code,
            ),
        )
        android.widget.Toast.makeText(
            activity,
            R.string.totp_code_copied,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openSwipeActions(row: SwipeRow) {
        if (openRow !== row) {
            openRow?.animateContentTo(0f)
        }
        openEntryId = row.entryId
        openRow = row
        row.animateContentTo(-row.actionsWidthPx.toFloat())
    }

    private fun closeSwipeActions(animated: Boolean = true) {
        val row = openRow
        openEntryId = null
        openRow = null
        if (animated) {
            row?.animateContentTo(0f)
        } else {
            row?.setContentTranslation(0f)
        }
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? SwipeRow) ?: SwipeRow()
            row.bind(getItem(position))
            return row.root
        }
    }

    private inner class SwipeRow {
        val actionsWidthPx = rowActionWidthPx * 2
        var entryId: String? = null
            private set

        private var entry: TotpEntry? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var downTranslation = 0f
        private var dragging = false

        private val titleView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, rowTextSizePx)
            setTextColor(ContextCompat.getColor(activity, android.R.color.black))
        }
        private val subtitleView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, rowSubtitleTextSizePx)
            setTextColor(ContextCompat.getColor(activity, R.color.row_subtitle_text))
        }
        private val codeView = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, rowCodeTextSizePx)
            setTextColor(ContextCompat.getColor(activity, android.R.color.black))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            letterSpacing = TOTP_CODE_LETTER_SPACING
        }
        private val progressLine = View(activity).apply {
            pivotX = 0f
            setBackgroundColor(ContextCompat.getColor(activity, R.color.row_timer_progress))
        }
        private val content = FrameLayout(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.app_surface))
            minimumHeight = rowMinHeightPx
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        rowPaddingHorizontalPx,
                        rowPaddingVerticalPx,
                        rowPaddingHorizontalPx,
                        rowPaddingVerticalPx + rowTimerHeightPx,
                    )
                    addView(
                        titleView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        subtitleView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        codeView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = rowNameCodeGapPx
                        },
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            addView(
                FrameLayout(activity).apply {
                    addView(
                        progressLine,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rowTimerHeightPx,
                ).apply {
                    gravity = Gravity.BOTTOM
                },
            )
            setOnTouchListener(::onContentTouch)
            setOnClickListener {
                val currentEntry = entry ?: return@setOnClickListener
                copyCurrentTotp(currentEntry)
            }
        }
        private val actionContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rowMinHeightPx
            setBackgroundColor(ContextCompat.getColor(activity, R.color.app_surface))
            addView(
                actionButtonSlot(
                    iconResId = R.drawable.ic_action_delete_24,
                    contentDescriptionResId = R.string.action_delete,
                    colorResId = R.color.row_action_delete,
                ) {
                    val selected = entry ?: return@actionButtonSlot
                    closeSwipeActions()
                    actions.deleteEntry(selected)
                },
                LinearLayout.LayoutParams(rowActionWidthPx, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                actionButtonSlot(
                    iconResId = R.drawable.ic_action_edit_24,
                    contentDescriptionResId = R.string.action_edit,
                    colorResId = R.color.row_action_edit,
                ) {
                    val selected = entry ?: return@actionButtonSlot
                    closeSwipeActions()
                    actions.editEntry(selected)
                },
                LinearLayout.LayoutParams(rowActionWidthPx, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        val root = FrameLayout(activity).apply {
            tag = this@SwipeRow
            minimumHeight = rowMinHeightPx
            addView(
                actionContainer,
                FrameLayout.LayoutParams(actionsWidthPx, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                },
            )
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun bind(entry: TotpEntry) {
            if (openRow === this && openEntryId != entry.id) {
                openRow = null
            }
            this.entry = entry
            entryId = entry.id
            titleView.text = entry.displayName
            val subtitle = entrySubtitle(entry)
            subtitleView.text = subtitle
            subtitleView.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
            updateTotp()
            content.contentDescription = entryLine(entry)
            content.animate().cancel()
            if (openEntryId == entry.id) {
                openRow = this
                setContentTranslation(-actionsWidthPx.toFloat())
            } else {
                setContentTranslation(0f)
            }
        }

        fun updateTotp(nowMillis: Long = System.currentTimeMillis()) {
            val currentEntry = entry ?: return
            val nowSeconds = nowMillis / 1000L
            codeView.text = Totp.generate(currentEntry, nowSeconds)
            val periodSeconds = currentEntry.periodSeconds
            val remainingSeconds = periodSeconds - (nowSeconds % periodSeconds).toInt()
            val progress = remainingSeconds.toFloat() / periodSeconds.toFloat()
            progressLine.scaleX = progress.coerceIn(0f, 1f)
            progressLine.setBackgroundColor(
                ContextCompat.getColor(
                    activity,
                    if (remainingSeconds <= TOTP_WARNING_SECONDS) {
                        R.color.row_timer_warning
                    } else {
                        R.color.row_timer_progress
                    },
                ),
            )
        }

        fun animateContentTo(translation: Float) {
            content.animate()
                .translationX(translation)
                .setDuration(SWIPE_ANIMATION_DURATION_MS)
                .start()
        }

        fun setContentTranslation(translation: Float) {
            content.animate().cancel()
            content.translationX = translation
        }

        private fun onContentTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (openEntryId != null && openEntryId != entryId) {
                        closeSwipeActions()
                    }
                    view.animate().cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTranslation = view.translationX
                    dragging = false
                    view.isPressed = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging &&
                        kotlin.math.abs(deltaX) > swipeTouchSlop &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)
                    ) {
                        dragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        view.translationX = (downTranslation + deltaX)
                            .coerceIn(-actionsWidthPx.toFloat(), 0f)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        if (view.translationX <= -actionsWidthPx / 2f) {
                            openSwipeActions(this)
                        } else {
                            closeSwipeActions()
                        }
                    } else if (openEntryId == entryId) {
                        closeSwipeActions()
                    } else {
                        view.performClick()
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (openEntryId == entryId) {
                        animateContentTo(-actionsWidthPx.toFloat())
                    } else {
                        animateContentTo(0f)
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }

        private fun actionButtonSlot(
            @DrawableRes iconResId: Int,
            contentDescriptionResId: Int,
            @ColorRes colorResId: Int,
            onClick: () -> Unit,
        ) = FrameLayout(activity).apply {
            minimumHeight = rowMinHeightPx
            addView(
                ImageButton(activity).apply {
                    val actionColor = ContextCompat.getColor(activity, colorResId)
                    background = circularActionFrame(actionColor)
                    imageTintList = ColorStateList.valueOf(actionColor)
                    setImageResource(iconResId)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = activity.getString(contentDescriptionResId)
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onClick() }
                },
                FrameLayout.LayoutParams(rowActionIconSizePx, rowActionIconSizePx).apply {
                    gravity = Gravity.CENTER
                },
            )
        }

        private fun circularActionFrame(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(rowActionIconStrokePx, color)
        }

        private fun entrySubtitle(entry: TotpEntry): String =
            listOf(entry.issuer, entry.accountName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

        private fun entryLine(entry: TotpEntry): String {
            val subtitle = entrySubtitle(entry)
            return if (subtitle.isBlank()) {
                activity.getString(R.string.entry_line_single, entry.displayName)
            } else {
                activity.getString(
                    R.string.entry_line_with_subtitle,
                    entry.displayName,
                    subtitle,
                )
            }
        }
    }

    private companion object {
        const val SWIPE_ANIMATION_DURATION_MS = 160L
        const val TICKER_INTERVAL_MS = 1_000L
        const val TOTP_WARNING_SECONDS = 5
        const val TOTP_CODE_LETTER_SPACING = 0.08f
    }
}
