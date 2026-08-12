package com.cloudx.databridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * "Select Area" bottom sheet for Virtual Routing — search box + single-select
 * radio list of areas, confirmed by the bottom "Select Area" button (matches
 * the mockup exactly).
 *
 * Caller sets [areas] and [currentSelection] and (usually) [onAreaSelected]
 * before calling show(childFragmentManager, ...). [onAreaSelected] fires once,
 * only from the confirm button — tapping ✕ or outside the sheet just dismisses
 * with no callback, same as the mockup's cancel affordance.
 *
 * Built programmatically (no XML) — the same way NotificationListBottomSheet
 * is, the one existing BottomSheetDialogFragment in this codebase. One outer
 * ScrollView wraps everything (header included), rather than nesting a second
 * scroll view just for the area list, to avoid nested-scroll conflicts; for
 * a short area list (the common case) nothing ends up scrolling at all.
 */
class SelectAreaBottomSheet : BottomSheetDialogFragment() {

    var areas: List<String> = emptyList()
    var currentSelection: String = ""
    var onAreaSelected: ((String) -> Unit)? = null

    private var selected: String = ""
    private lateinit var listContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        selected = currentSelection

        val root = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 18.dp(), 20.dp(), 20.dp())
        }
        root.addView(column)

        // ── Header row: "Select Area" + close ──────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 14.dp() }
        }

        val tvTitle = TextView(ctx).apply {
            text = "Select Area"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvClose = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            setTextColor(ctx.getColor(R.color.theme_text_secondary))
            setPadding(10.dp(), 4.dp(), 4.dp(), 4.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }

        headerRow.addView(tvTitle)
        headerRow.addView(tvClose)
        column.addView(headerRow)

        // ── Search box ──────────────────────────────────────────────────
        val etSearch = EditText(ctx).apply {
            hint = "Search area"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setHintTextColor(ctx.getColor(R.color.theme_text_muted))
            setBackgroundResource(R.drawable.bg_search_bar)
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 6.dp() }
        }
        column.addView(etSearch)

        // ── Area list ───────────────────────────────────────────────────
        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(listContainer)
        renderList(ctx, dp, areas)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val filtered = if (q.isBlank()) areas else areas.filter { it.contains(q, ignoreCase = true) }
                renderList(ctx, dp, filtered)
            }
        })

        // ── Confirm button ──────────────────────────────────────────────
        val btnConfirm = TextView(ctx).apply {
            text = "Select Area"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_inverse))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_validate_purple)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp()
            ).also { it.topMargin = 14.dp() }
            setOnClickListener {
                onAreaSelected?.invoke(selected)
                dismiss()
            }
        }
        column.addView(btnConfirm)

        return root
    }

    private fun renderList(ctx: Context, dp: Float, list: List<String>) {
        fun Int.dp() = (this * dp).toInt()
        listContainer.removeAllViews()

        if (list.isEmpty()) {
            listContainer.addView(TextView(ctx).apply {
                text = "কোনো area পাওয়া যায়নি"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ctx.getColor(R.color.theme_text_secondary))
                setPadding(0, 24.dp(), 0, 24.dp())
            })
            return
        }

        list.forEachIndexed { index, area ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setPadding(2.dp(), 12.dp(), 2.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvArea = TextView(ctx).apply {
                text = area
                textSize = 14f
                setTextColor(ctx.getColor(R.color.theme_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val radio = RadioButton(ctx).apply {
                isClickable = false
                isChecked = area == selected
                buttonTintList = ColorStateList.valueOf(ctx.getColor(R.color.theme_purple))
            }

            row.addView(tvArea)
            row.addView(radio)
            row.setOnClickListener {
                selected = area
                renderList(ctx, dp, list)
            }
            listContainer.addView(row)

            if (index != list.lastIndex) {
                listContainer.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
                    )
                    setBackgroundColor(ctx.getColor(R.color.theme_divider))
                })
            }
        }
    }
}
