package com.cloudx.databridge

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Searchable BottomSheetDialog for picking a Google Drive spreadsheet from a list.
 * Shared by ConfigSheetFragment and ConfigScannerSheetFragment — both need identical UI
 * (drag handle, search bar with live filtering, selected-state styling) but have different
 * downstream logic once a sheet is picked (ConfigSheetFragment also loads tabs for sync
 * mapping; ConfigScannerSheetFragment just records the selection), so this takes a plain
 * onSelect callback rather than knowing about either fragment's own state.
 */
object SheetPickerDialog {

    fun show(
        context: Context,
        sheets: List<DriveFile>,
        currentSelection: DriveFile?,
        onSelect: (DriveFile) -> Unit
    ) {
        val ctx = context
        val dp = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // ── Root container ────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ── Drag handle ───────────────────────────────────────────────
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(ctx.getColor(R.color.theme_border))
                cornerRadius = 4.dp().toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(48.dp(), 4.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 10.dp()
                bottomMargin = 10.dp()
            }
        }
        val handleWrapper = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(handle)
        }
        root.addView(handleWrapper)

        // ── Header ────────────────────────────────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp(), 4.dp(), 12.dp(), 12.dp())
        }
        val tvTitle = TextView(ctx).apply {
            text = "Google Sheet বেছে নিন"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvCount = TextView(ctx).apply {
            text = "${sheets.size} sheets"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setPadding(0, 0, 8.dp(), 0)
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvCount)
        root.addView(headerRow)

        // ── Search bar ────────────────────────────────────────────────
        val searchWrapper = FrameLayout(ctx).apply {
            setPadding(16.dp(), 0, 16.dp(), 12.dp())
        }
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ctx.getColor(R.color.theme_bg_inner))
                setStroke(2, ctx.getColor(R.color.theme_border))
                cornerRadius = 12.dp().toFloat()
            }
            setPadding(14.dp(), 0, 14.dp(), 0)
        }
        val tvSearchIcon = TextView(ctx).apply {
            text = "🔍"
            textSize = 14f
            setPadding(0, 0, 8.dp(), 0)
        }
        val etSearch = EditText(ctx).apply {
            hint = "Sheet এর নাম লিখুন..."
            setSingleLine(true)
            background = null
            textSize = 14f
            setTextColor(ctx.getColor(R.color.theme_text_primary))
            setHintTextColor(ctx.getColor(R.color.theme_text_muted))
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f)
        }
        val tvClear = TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            setPadding(8.dp(), 0, 0, 0)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        searchRow.addView(tvSearchIcon)
        searchRow.addView(etSearch)
        searchRow.addView(tvClear)
        searchWrapper.addView(searchRow)
        root.addView(searchWrapper)

        // ── Divider ───────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.theme_bg_inner))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Sheet list ────────────────────────────────────────────────
        var filteredSheets = sheets.toMutableList()

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 10.dp(), 16.dp(), 24.dp())
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        val tvEmpty = TextView(ctx).apply {
            text = "🔍 কোনো sheet পাওয়া যায়নি"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.theme_text_muted))
            gravity = Gravity.CENTER
            setPadding(0, 32.dp(), 0, 0)
            visibility = View.GONE
        }
        listContainer.addView(tvEmpty)

        fun buildSheetItem(sheet: DriveFile, isSelected: Boolean): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSelected)
                    ctx.resources.getDrawable(R.drawable.bg_sheet_item_selected, null)
                else
                    ctx.resources.getDrawable(R.drawable.bg_sheet_item_normal, null)
                setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8.dp()
                layoutParams = lp
                isClickable = true
                isFocusable = true

                val tvIcon = TextView(ctx).apply {
                    text = "📊"
                    textSize = 18f
                    setPadding(0, 0, 12.dp(), 0)
                }
                val tvName = TextView(ctx).apply {
                    text = sheet.name
                    textSize = 13f
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) Color.parseColor("#E8380D") else ctx.getColor(R.color.theme_text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvCheck = TextView(ctx).apply {
                    text = "✓"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#E8380D"))
                    visibility = if (isSelected) View.VISIBLE else View.GONE
                }
                addView(tvIcon); addView(tvName); addView(tvCheck)
            }
        }

        fun rebuildList(dialog: android.app.Dialog) {
            while (listContainer.childCount > 1) listContainer.removeViewAt(1)
            if (filteredSheets.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                return
            }
            tvEmpty.visibility = View.GONE
            filteredSheets.forEach { sheet ->
                val isSelected = currentSelection?.id == sheet.id
                val item = buildSheetItem(sheet, isSelected)
                item.setOnClickListener {
                    onSelect(sheet)
                    dialog.dismiss()
                }
                listContainer.addView(item)
            }
        }

        // ── Dialog ────────────────────────────────────────────────────
        val dialog = android.app.Dialog(ctx, com.google.android.material.R.style.Theme_MaterialComponents_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.85).toInt())
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(24.dp().toFloat(), 24.dp().toFloat(), 24.dp().toFloat(), 24.dp().toFloat(), 0f, 0f, 0f, 0f)
            }
            attributes?.windowAnimations = android.R.style.Animation_InputMethod
        }

        rebuildList(dialog)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                tvClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                filteredSheets = if (query.isEmpty()) sheets.toMutableList()
                else sheets.filter { it.name.lowercase().contains(query) }.toMutableList()
                rebuildList(dialog)
            }
        })

        tvClear.setOnClickListener {
            etSearch.setText("")
            etSearch.requestFocus()
        }

        dialog.show()
        etSearch.requestFocus()
    }
}
