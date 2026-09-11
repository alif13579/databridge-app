package com.cloudx.databridge

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Routing Approval — sheet LIVE via socket bindings.
 *
 * Agent A opens this fragment and sees, for THEIR branch(es):
 * - Incoming tab: parcels other branches sent to this branch
 *   (sheet To == own branch). Every card has decision buttons:
 *   Approved / Wrong hub / Improper address (in-memory, same as before).
 * - Outgoing tab: parcels this branch sent out (sheet From == own branch).
 *   Info only — the RECEIVING branch approves on their own Incoming tab.
 *
 * Data path (extension parity: ID=D, From=C, To=E, Confirm=K):
 * 1. 🔌 socket binds a sheet LIBRARY + column letters per branch
 *    (`config/sheetBindings/{branchId}/routing`, same pattern as CC 🔌).
 * 2. Fetch reads the bound tab's rows → IDs (+ from/to/confirm cells).
 * 3. Each ID is read from Firebase `courier/consignments/{id}` for the real
 *    card info (customer/phone/address/COD/status) — sheet only routes.
 *
 * Destination wins (same as extension): To == own → incoming, else
 * From == own → outgoing. Other branches' rows are skipped.
 */
class RoutingApprovalFragment : Fragment() {

    private val myBranchIds: List<String>
        get() = RbacManager.current.branchIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private data class RouteParcel(
        val id: String,
        val fromBranchId: String,
        val fromBranchName: String,
        val toBranchId: String,
        val toBranchName: String,
        val customer: String,
        val phone: String,
        val address: String,
        val cod: Int,
        val fbStatus: String,
        val confirm: String,
        val foundInFirebase: Boolean,
        var decision: String = "", // "", "approved", "wrong_hub", "improper_address"
    )

    private data class SheetRouteRow(
        val id: String,
        val from: String,
        val to: String,
        val confirm: String,
    )

    private val incoming = mutableListOf<RouteParcel>()
    private val outgoing = mutableListOf<RouteParcel>()
    private var tab = "incoming"
    private var loading = false

    private var tabIncomingBtn: TextView? = null
    private var tabOutgoingBtn: TextView? = null
    private var tvStatus: TextView? = null
    private var cardsBox: LinearLayout? = null

    private val http by lazy { okhttp3.OkHttpClient() }

    private fun dp(v: Int): Int =
        (v * (resources.displayMetrics.density)).toInt()

    // ── UI ───────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = "Routing Approval"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "🔌"
            textSize = 20f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openSocketDialog() }
        })
        titleRow.addView(TextView(ctx).apply {
            text = "⟳"
            textSize = 20f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { load() }
        })
        root.addView(titleRow)

        tvStatus = TextView(ctx).apply {
            text = "⏳ Sheet পড়ছে..."
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(2), 0, dp(12))
        }
        root.addView(tvStatus)

        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        tabIncomingBtn = tabBtn(ctx, "").apply {
            setOnClickListener { tab = "incoming"; render() }
        }
        tabOutgoingBtn = tabBtn(ctx, "").apply {
            setOnClickListener { tab = "outgoing"; render() }
        }
        tabRow.addView(tabIncomingBtn)
        tabRow.addView(tabOutgoingBtn)
        root.addView(tabRow)

        cardsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(cardsBox)
        render()
        load()
        return scroll
    }

    private fun setStatus(text: String) {
        tvStatus?.text = text
    }

    private fun tabBtn(ctx: android.content.Context, label: String): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    // ── Load: branches → directory → bindings → sheet rows → Firebase ────────
    private fun load() {
        if (loading) return
        loading = true
        setStatus("⏳ Sheet পড়ছে...")
        lifecycleScope.launch {
            try {
                val branches = myBranchIds
                if (branches.isEmpty()) {
                    setStatus("⚠ কোনো branch assigned নেই — admin-এর সাথে যোগাযোগ করুন")
                    return@launch
                }
                // Branch directory (id → name) for own-name matching + display.
                val idToName = mutableMapOf<String, String>()
                runCatching { SupabaseClaimsReader.fetchBranches() }.getOrNull().orEmpty()
                    .forEach { opt ->
                        if (opt.branchId.isNotBlank() && opt.name.isNotBlank()) {
                            idToName[opt.branchId] = opt.name
                        }
                    }
                branches.forEach { idToName.putIfAbsent(it, it) }
                val ownTokens = (branches + branches.mapNotNull { idToName[it] })
                    .map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

                val appCtx = requireContext().applicationContext
                val token = withContext(Dispatchers.IO) { RemarkSheetMirror.readToken(appCtx) }
                if (!isAdded) return@launch
                if (token.isNullOrBlank()) {
                    (activity as? MainActivity)?.promptSheetAuthOnce()
                    setStatus("Sheet auth নেই — Google connect করে ⟳ চাপুন")
                    return@launch
                }
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))
                val targets = withContext(Dispatchers.IO) {
                    branches.flatMap { SheetLibraryRepository.resolveRoutingTargets(it, today) }
                }
                if (!isAdded) return@launch
                if (targets.isEmpty()) {
                    incoming.clear()
                    outgoing.clear()
                    render()
                    setStatus("🔌 চাপ দিয়ে sheet bind করো — কোনো routing binding নেই")
                    return@launch
                }
                // Fetch rows per bound sheet (parallel), then classify.
                val rows = withContext(Dispatchers.IO) {
                    coroutineScope {
                        targets.map { t -> async { fetchRoutingRows(token, t) } }
                            .flatMap { it.await() }
                    }
                }
                if (!isAdded) return@launch
                val seen = mutableSetOf<String>()
                val inRows = mutableListOf<SheetRouteRow>()
                val outRows = mutableListOf<SheetRouteRow>()
                rows.forEach { r ->
                    if (r.id.isBlank() || !seen.add(r.id)) return@forEach
                    val toMine = r.to.trim().lowercase() in ownTokens
                    val fromMine = r.from.trim().lowercase() in ownTokens
                    // Destination wins (extension parity).
                    when {
                        toMine -> inRows.add(r)
                        fromMine -> outRows.add(r)
                        // else: another branch's row — skip.
                    }
                }
                // Firebase enrich per ID (parallel) for real card info.
                val parcels = withContext(Dispatchers.IO) {
                    coroutineScope {
                        (inRows + outRows).distinctBy { it.id }.map { r ->
                            async { enrichFromFirebase(r, idToName) }
                        }.map { it.await() }
                    }
                }
                if (!isAdded) return@launch
                val inIds = inRows.map { it.id }.toSet()
                // Keep in-memory decisions across reloads for same IDs.
                val oldDecisions = (incoming + outgoing).associate { it.id to it.decision }
                incoming.clear()
                outgoing.clear()
                parcels.forEach { p ->
                    oldDecisions[p.id]?.takeIf { it.isNotBlank() }?.let { p.decision = it }
                    if (p.id in inIds) incoming.add(p) else outgoing.add(p)
                }
                render()
                val missCount = parcels.count { !it.foundInFirebase }
                setStatus(
                    if (parcels.isEmpty()) "Sheet খালি — এই branch-এর কোনো row নেই"
                    else "✓ ${incoming.size} incoming • ${outgoing.size} outgoing" +
                        if (missCount > 0) " • $missCount টি Firebase-এ নেই" else ""
                )
            } catch (e: Exception) {
                if (!isAdded) return@launch
                setStatus("✕ Load failed: ${e.message?.take(80) ?: "error"}")
            } finally {
                loading = false
            }
        }
    }

    /** Reads one bound sheet tab's routing rows: ID + from/to/confirm cells. */
    private suspend fun fetchRoutingRows(
        token: String,
        target: SheetLibraryRepository.RoutingTarget,
    ): List<SheetRouteRow> = withContext(Dispatchers.IO) {
        try {
            val lib = target.library
            val binding = target.binding
            val tabName = ScannerSheetRepository.resolveTabName(lib.tabPattern)
            val headerRow = lib.resolvedHeaderRow()
            fun letterOf(ref: SheetColRef): String? {
                val t = ref.colRef.trim()
                if (t.isEmpty()) return null
                if (ref.mode != SheetColMode.TEXT) {
                    if (Regex("^[A-Za-z]{1,3}$").matches(t)) return t.uppercase()
                    val idx = ConfigSheetParseUtil.parseColInput(t) ?: return null
                    return ConfigSheetParseUtil.colIndexToLetter(idx)
                }
                val headers = ConfigSheetDriveApi.fetchRowValues(token, lib.sheetId, tabName, headerRow, http)
                val idx = headers.indexOfFirst { it.trim() == t }
                if (idx < 0) return null
                return ConfigSheetParseUtil.colIndexToLetter(idx + 1)
            }
            val idLetter = letterOf(binding.idCol) ?: return@withContext emptyList()
            val fromLetter = letterOf(binding.fromCol)
            val toLetter = letterOf(binding.toCol)
            val confirmLetter = letterOf(binding.confirmCol)
            fun col(letter: String?): List<String> =
                if (letter.isNullOrBlank()) emptyList()
                else runCatching {
                    ConfigSheetDriveApi.fetchColumnValues(token, lib.sheetId, tabName, letter, http)
                }.getOrDefault(emptyList())
            val idCol = col(idLetter)
            val fromCol = col(fromLetter)
            val toCol = col(toLetter)
            val confirmCol = col(confirmLetter)
            val rowCount = listOf(idCol.size, fromCol.size, toCol.size, confirmCol.size).maxOrNull() ?: 0
            buildList {
                // values[i] == sheet row i+1 → data starts at headerRow index.
                for (i in headerRow until rowCount) {
                    val id = idCol.getOrNull(i).orEmpty().trim()
                    if (id.isEmpty()) continue
                    add(SheetRouteRow(
                        id = id,
                        from = fromCol.getOrNull(i).orEmpty().trim(),
                        to = toCol.getOrNull(i).orEmpty().trim(),
                        confirm = confirmCol.getOrNull(i).orEmpty().trim(),
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Fills card info from Firebase; sheet route text stays as-is. */
    private suspend fun enrichFromFirebase(
        row: SheetRouteRow,
        idToName: Map<String, String>,
    ): RouteParcel = withContext(Dispatchers.IO) {
        fun nameToId(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return ""
            val c = SupabaseBranchReader.canonicalBranchIdLocal(t, idToName)
            return if (idToName.containsKey(c)) c else ""
        }
        try {
            val snap = FirebaseDatabase.getInstance()
                .reference.child("courier/consignments/${row.id}").get().await()
            if (!snap.exists()) {
                return@withContext RouteParcel(
                    id = row.id, fromBranchId = nameToId(row.from), fromBranchName = row.from,
                    toBranchId = nameToId(row.to), toBranchName = row.to,
                    customer = "—", phone = "", address = "", cod = 0,
                    fbStatus = "", confirm = row.confirm, foundInFirebase = false,
                )
            }
            val cod = snap.child("collectableAmount").getValue(String::class.java)
                ?.toDoubleOrNull()?.toInt()
                ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0
            RouteParcel(
                id = row.id, fromBranchId = nameToId(row.from), fromBranchName = row.from,
                toBranchId = nameToId(row.to), toBranchName = row.to,
                customer = snap.child("recipientName").getValue(String::class.java).orEmpty().ifBlank { "—" },
                phone = snap.child("recipientPhone").getValue(String::class.java).orEmpty(),
                address = snap.child("recipientAddress").getValue(String::class.java).orEmpty(),
                cod = cod,
                fbStatus = snap.child("status").getValue(String::class.java).orEmpty(),
                confirm = row.confirm, foundInFirebase = true,
            )
        } catch (_: Exception) {
            RouteParcel(
                id = row.id, fromBranchId = nameToId(row.from), fromBranchName = row.from,
                toBranchId = nameToId(row.to), toBranchName = row.to,
                customer = "—", phone = "", address = "", cod = 0,
                fbStatus = "", confirm = row.confirm, foundInFirebase = false,
            )
        }
    }

    // ── 🔌 Socket: bind library + columns ────────────────────────────────────
    private fun openSocketDialog() {
        val ctx = requireContext()
        val branches = myBranchIds
        if (branches.isEmpty()) {
            Toast.makeText(ctx, "কোনো branch assigned নেই", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val idToName = mutableMapOf<String, String>()
            runCatching { SupabaseClaimsReader.fetchBranches() }.getOrNull().orEmpty()
                .forEach { opt ->
                    if (opt.branchId.isNotBlank() && opt.name.isNotBlank()) {
                        idToName[opt.branchId] = opt.name
                    }
                }
            if (!isAdded) return@launch
            val branchLabels = branches.map { "${idToName[it] ?: it} ($it)" }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            fun label(t: String) = TextView(ctx).apply {
                text = t
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#374151"))
                setPadding(0, dp(10), 0, dp(2))
            }
            box.addView(label("Branch"))
            val spBranch = Spinner(ctx)
            spBranch.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, branchLabels)
            box.addView(spBranch)
            box.addView(label("Sheet library"))
            val spLib = Spinner(ctx)
            box.addView(spLib)
            var libs = listOf<SheetLibrary>()
            suspend fun reloadLibs(branchId: String): List<SheetLibrary> =
                SheetLibraryRepository.loadLibraries(branchId).filter { it.enabled }
            suspend fun refreshLibSpinner(branchId: String) {
                libs = withContext(Dispatchers.IO) { reloadLibs(branchId) }
                if (!isAdded) return
                spLib.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                    if (libs.isEmpty()) listOf("— কোনো library নেই —")
                    else libs.map { it.nickname.ifBlank { it.sheetName }.ifBlank { it.libraryId } })
            }
            spBranch.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    lifecycleScope.launch { refreshLibSpinner(branches[pos]) }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            fun colInput(def: String) = EditText(ctx).apply {
                setText(def)
                hint = "Column letter (D)"
                setSingleLine()
            }
            box.addView(label("ID column (D)"))
            val etId = colInput("D")
            box.addView(etId)
            box.addView(label("From column (C)"))
            val etFrom = colInput("C")
            box.addView(etFrom)
            box.addView(label("To column (E)"))
            val etTo = colInput("E")
            box.addView(etTo)
            box.addView(label("Confirm column (K)"))
            val etConfirm = colInput("K")
            box.addView(etConfirm)
            val cbEnabled = CheckBox(ctx).apply {
                text = "Enabled"
                isChecked = true
            }
            box.addView(cbEnabled)

            // Prefill when a binding already exists for the picked library.
            var currentBinding: RoutingBinding? = null
            suspend fun refreshBindingPrefill() {
                val bi = spBranch.selectedItemPosition.coerceAtLeast(0)
                val li = spLib.selectedItemPosition.coerceAtLeast(0)
                val branchId = branches.getOrNull(bi) ?: return
                val lib = libs.getOrNull(li) ?: return
                currentBinding = withContext(Dispatchers.IO) {
                    SheetLibraryRepository.loadRoutingBindings(branchId)
                        .firstOrNull { it.libraryId == lib.libraryId }
                }
                if (!isAdded) return
                etId.setText(currentBinding?.idCol?.colRef?.ifBlank { "D" } ?: "D")
                etFrom.setText(currentBinding?.fromCol?.colRef?.ifBlank { "C" } ?: "C")
                etTo.setText(currentBinding?.toCol?.colRef?.ifBlank { "E" } ?: "E")
                etConfirm.setText(currentBinding?.confirmCol?.colRef?.ifBlank { "K" } ?: "K")
                cbEnabled.isChecked = currentBinding?.enabled ?: true
            }
            spLib.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    lifecycleScope.launch { refreshBindingPrefill() }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            lifecycleScope.launch { refreshLibSpinner(branches[0]) }

            val dialog = AlertDialog.Builder(ctx)
                .setTitle("🔌 Routing socket — sheet bind")
                .setView(box)
                .setPositiveButton("💾 Save", null)
                .setNeutralButton("🗑 Delete", null)
                .setNegativeButton("Close", null)
                .create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val bi = spBranch.selectedItemPosition
                val li = spLib.selectedItemPosition
                val branchId = branches.getOrNull(bi)
                val lib = libs.getOrNull(li)
                if (branchId.isNullOrBlank() || lib == null) {
                    Toast.makeText(ctx, "Branch + library বেছে নিন", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    try {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                        val actingName = withContext(Dispatchers.IO) {
                            runCatching {
                                FirebaseDatabase.getInstance()
                                    .reference.child("users/$uid/profile/name")
                                    .get().await().getValue(String::class.java)
                            }.getOrNull().orEmpty()
                        }
                        withContext(Dispatchers.IO) {
                            SheetLibraryRepository.saveRoutingBinding(
                                RoutingBinding(
                                    bindingId = currentBinding?.bindingId.orEmpty(),
                                    libraryId = lib.libraryId,
                                    branchId = branchId,
                                    idCol = SheetColRef(etId.text.toString().trim().ifBlank { "D" }),
                                    fromCol = SheetColRef(etFrom.text.toString().trim().ifBlank { "C" }),
                                    toCol = SheetColRef(etTo.text.toString().trim().ifBlank { "E" }),
                                    confirmCol = SheetColRef(etConfirm.text.toString().trim().ifBlank { "K" }),
                                    enabled = cbEnabled.isChecked,
                                ),
                                uid, actingName,
                            )
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, "✅ Routing binding saved", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        load()
                    } catch (e: Exception) {
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val b = currentBinding
                if (b == null) {
                    Toast.makeText(ctx, "Delete করার মতো binding নেই", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SheetLibraryRepository.deleteRoutingBinding(b.branchId, b.bindingId)
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, "🗑 Binding deleted", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        load()
                    } catch (e: Exception) {
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ── Render (same cards as before, now with Firebase info) ───────────────
    private fun render() {
        val ctx = context ?: return
        val inCount = incoming.size
        val outCount = outgoing.size
        tabIncomingBtn?.text = "⬇ Incoming ($inCount)"
        tabOutgoingBtn?.text = "⬆ Outgoing ($outCount)"
        val selBg = "#16A34A"
        val idleBg = "#E5E7EB"
        tabIncomingBtn?.apply {
            setBackgroundColor(Color.parseColor(if (tab == "incoming") selBg else idleBg))
            setTextColor(Color.parseColor(if (tab == "incoming") "#FFFFFF" else "#374151"))
        }
        tabOutgoingBtn?.apply {
            setBackgroundColor(Color.parseColor(if (tab == "outgoing") selBg else idleBg))
            setTextColor(Color.parseColor(if (tab == "outgoing") "#FFFFFF" else "#374151"))
        }
        val box = cardsBox ?: return
        box.removeAllViews()
        val list = if (tab == "incoming") incoming else outgoing
        if (list.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = if (tab == "incoming") "Ajke kono incoming parcel nei"
                else "Ajke kono outgoing parcel nei"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        list.forEach { parcel ->
            box.addView(parcelCard(ctx, parcel, tab == "incoming"))
        }
    }

    private fun decisionChip(parcel: RouteParcel): Pair<String, Pair<String, String>> = when (parcel.decision) {
        "approved" -> "✓ Approved" to ("#15803D" to "#DCFCE7")
        "wrong_hub" -> "✕ Wrong hub" to ("#B91C1C" to "#FEE2E2")
        "improper_address" -> "⚠ Improper address" to ("#C2410C" to "#FFEDD5")
        else -> "⏳ Pending" to ("#B45309" to "#FEF3C7")
    }

    private fun parcelCard(ctx: android.content.Context, parcel: RouteParcel, isIncoming: Boolean): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_card_rounded)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(ctx).apply {
            text = parcel.id
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val (chipText, colors) = if (isIncoming) decisionChip(parcel)
        else "➡ Sent" to ("#1D4ED8" to "#DBEAFE")
        topRow.addView(TextView(ctx).apply {
            text = chipText
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(colors.first))
            setBackgroundColor(Color.parseColor(colors.second))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        card.addView(topRow)

        fun metaLine(text: String) {
            card.addView(TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        metaLine("🔀 ${parcel.fromBranchName.ifBlank { "?" }} → ${parcel.toBranchName.ifBlank { "?" }}")
        metaLine("👤 ${parcel.customer}" + if (parcel.phone.isNotBlank()) " • ${parcel.phone}" else "")
        if (parcel.address.isNotBlank()) metaLine("📍 ${parcel.address}")
        metaLine("💰 COD ৳${parcel.cod}" +
            if (parcel.fbStatus.isNotBlank()) " • ${parcel.fbStatus}" else "")
        if (parcel.confirm.isNotBlank()) metaLine("📋 Sheet: ${parcel.confirm}")
        if (!parcel.foundInFirebase) metaLine("⚠ Firebase-এ পাওয়া যায়নি — sheet ID মাত্র")

        if (isIncoming) {
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            btnRow.addView(actionBtn(ctx, "✓ Approved", "#DCFCE7", "#15803D") {
                parcel.decision = "approved"
                Toast.makeText(ctx, "${parcel.id} approved", Toast.LENGTH_SHORT).show()
                render()
            })
            btnRow.addView(actionBtn(ctx, "✕ Wrong hub", "#FEE2E2", "#B91C1C") {
                parcel.decision = "wrong_hub"
                Toast.makeText(ctx, "${parcel.id} — wrong hub", Toast.LENGTH_SHORT).show()
                render()
            })
            btnRow.addView(actionBtn(ctx, "⚠ Improper address", "#FFEDD5", "#C2410C") {
                parcel.decision = "improper_address"
                Toast.makeText(ctx, "${parcel.id} — improper address", Toast.LENGTH_SHORT).show()
                render()
            })
            card.addView(btnRow)
        } else {
            card.addView(TextView(ctx).apply {
                text = "Receiving branch (${parcel.toBranchName.ifBlank { "?" }}) approve korbe"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun actionBtn(
        ctx: android.content.Context, label: String, bg: String, fg: String, onTap: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(fg))
        setBackgroundColor(Color.parseColor(bg))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (label != "✓ Approved") leftMargin = dp(8)
        }
        setOnClickListener { onTap() }
    }
}
