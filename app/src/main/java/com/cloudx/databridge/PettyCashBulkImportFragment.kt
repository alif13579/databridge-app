package com.cloudx.databridge

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Calendar
import java.util.Locale

/**
 * Petty Cash — Bulk Import (Accounts-only).
 *
 * Lets Accounts submit many claims at once from an .xlsx / .csv file with
 * the same columns as the report export (Date, From, To, Vehicle, Agent ID,
 * Agent Name, Type, Consignment/Merchant, LOT ID, Requested). Flow:
 * pick → parse → validate (agents, categories, dates, conveyance From/To)
 * → preview → submit each valid row as a pending claim through the normal
 * claim_upsert path (approve → settle flow untouched). Each row carries its
 * own client_submit_id so a retry never duplicates.
 *
 * xlsx is parsed dependency-free (zip + XmlPullParser): inline strings,
 * shared strings and plain numbers; dates as "26-Aug-26" text, ISO text or
 * Excel serials.
 */
class PettyCashBulkImportFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()
    private var branchId: String = ""
    private var isAccounts: Boolean = false

    private data class ImportRow(
        val lineNo: Int,
        val dateMillis: Long,
        val dateIso: String,
        val fromArea: String,
        val toArea: String,
        val vehicle: String,
        val agent: SupabaseClaimsReader.BranchAgent,
        val category: String,
        val cidOrMerchant: String,
        val storeId: String,
        val approved: Double,
        val settled: Double,
        val attempted: Int,
        val succeeded: Int,
        val pickupCount: Int,
    )

    private var validRows: List<ImportRow> = emptyList()
    private var invalidLines: List<String> = emptyList()
    private var submitting: Boolean = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_petty_cash_bulk_import, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        view.findViewById<View>(R.id.btnPcBulkBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<Button>(R.id.btnPcBulkPick).setOnClickListener { pickFile.launch("*/*") }
        view.findViewById<Button>(R.id.btnPcBulkSample).setOnClickListener { downloadSample() }
        view.findViewById<Button>(R.id.btnPcBulkSubmit).setOnClickListener { confirmSubmit() }
        if (branchId.isBlank()) {
            toast("No branch selected")
            parentFragmentManager.popBackStack()
            return
        }
        viewModel.state.observe(viewLifecycleOwner) { state ->
            if (state is PettyCashState.Success) {
                isAccounts = state.roles.isAccounts
                if (!isAccounts) {
                    toast("Only Accounts can bulk-import claims")
                    parentFragmentManager.popBackStack()
                }
            }
        }
        viewModel.load(branchId)
    }

    // ── File loading + parsing ─────────────────────────────────────────────

    private fun loadFile(uri: Uri) {
        val v = view ?: return
        val name = displayName(uri).orEmpty()
        if (!name.endsWith(".xlsx", ignoreCase = true) && !name.endsWith(".csv", ignoreCase = true)) {
            toast("Pick an .xlsx or .csv file")
            return
        }
        v.findViewById<TextView>(R.id.tvPcBulkFile).text = "Reading $name…"
        v.findViewById<Button>(R.id.btnPcBulkSubmit).isEnabled = false
        lifecycleScope.launch {
            val grid = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read file")
                    if (name.endsWith(".csv", ignoreCase = true)) parseCsv(bytes) else parseXlsx(bytes)
                }
            }.getOrElse {
                v.findViewById<TextView>(R.id.tvPcBulkFile).text = "No file picked"
                toast("Could not parse file: ${it.message}")
                return@launch
            }
            v.findViewById<TextView>(R.id.tvPcBulkFile).text = "$name · ${maxOf(0, grid.size - 1)} data rows"
            validateGrid(grid)
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment?.substringAfterLast('/')
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun parseCsv(bytes: ByteArray): List<List<String>> {
        val text = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
        val rows = mutableListOf<List<String>>()
        var cur = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ }
                    else inQuotes = !inQuotes
                }
                ch == ',' && !inQuotes -> { row.add(cur.toString()); cur = StringBuilder() }
                ch == '\n' && !inQuotes -> { row.add(cur.toString()); cur = StringBuilder(); rows.add(row); row = mutableListOf() }
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Minimal .xlsx reader: first worksheet only. Handles inline strings,
     *  shared strings (t="s") and plain numbers; missing cells are blank. */
    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        val zip = java.util.zip.ZipInputStream(bytes.inputStream())
        var shared = emptyList<String>()
        var sheetXml: String? = null
        var entry = zip.nextEntry
        while (entry != null) {
            when (entry.name) {
                "xl/sharedStrings.xml" -> shared = readSharedStrings(zip.readBytes())
                "xl/worksheets/sheet1.xml" -> sheetXml = zip.readBytes().toString(Charsets.UTF_8)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        zip.close()
        val xml = sheetXml ?: error("No worksheet found (sheet1)")
        return readSheet(xml, shared)
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val f = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        f.setInput(bytes.inputStream(), "UTF-8")
        var text = StringBuilder()
        var inT = false
        var ev = f.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> if (f.name == "t") { inT = true; text = StringBuilder() }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inT) text.append(f.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (f.name) {
                    "t" -> inT = false
                    "si" -> out.add(text.toString())
                }
            }
            ev = f.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref.takeWhile { it.isLetter() }) n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun readSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val f = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        f.setInput(xml.reader())
        var cur = mutableListOf<String>()
        var cellType: String? = null
        var cellText = StringBuilder()
        var inV = false
        var inT = false
        var ev = f.eventType
        fun putCell(ref: String, value: String) {
            val idx = colIndex(ref)
            while (cur.size <= idx) cur.add("")
            cur[idx] = value
        }
        var cellRef = ""
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (f.name) {
                    "row" -> cur = mutableListOf()
                    "c" -> {
                        cellRef = f.getAttributeValue(null, "r").orEmpty()
                        cellType = f.getAttributeValue(null, "t")
                        cellText = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inV || inT) cellText.append(f.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (f.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = cellText.toString()
                        val value = when (cellType) {
                            "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else -> raw
                        }
                        if (cellRef.isNotBlank()) putCell(cellRef, value)
                    }
                    "row" -> { if (cur.any { it.isNotBlank() }) rows.add(cur.toList()) }
                }
            }
            ev = f.next()
        }
        return rows
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private fun normHeader(h: String): String = h.trim().lowercase().replace("[^a-z/]".toRegex(), "")

    private fun validateGrid(grid: List<List<String>>) {
        val v = view ?: return
        lifecycleScope.launch {
            v.findViewById<TextView>(R.id.tvPcBulkPreview).text = "Validating…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    require(grid.isNotEmpty()) { "Empty file" }
                    val head = grid.first().map { normHeader(it) }
                    fun col(vararg names: String): Int {
                        names.forEach { n -> head.indexOfFirst { it == n }.takeIf { it >= 0 }?.let { return it } }
                        return -1
                    }
                    val cDate = col("date")
                    val cFrom = col("from")
                    val cTo = col("to")
                    val cVehicle = col("vehicle")
                    val cEmpId = col("agentid", "empid", "employeeid")
                    val cName = col("agentname", "employee", "name", "agent")
                    val cCat = col("type", "category")
                    val cCid = col("consignment/merchant", "consignmentmerchant", "consignment", "merchant")
                    val cStore = col("lotid", "store", "storeid")
                    val cReq = col("requested")
                    val cAppr = col("approved")
                    val cSet = col("settled")
                    val cAttempt = col("attempt", "attempted", "attemptedqty", "attemptqty", "attemptquantity")
                    val cSuccess = col("success", "succeeded", "succeededqty", "successqty", "delivered", "deliveredqty", "deliveredquantity")
                    require(cDate >= 0 && cCat >= 0 && cAppr >= 0 && cSet >= 0) { "Need Date, Type, Approved and Settled columns" }
                    require(cEmpId >= 0) { "Need Agent ID column" }
                    require(cCid >= 0) { "Need Consignment/Merchant column" }
                    val agents = SupabaseClaimsReader.fetchBranchAgents(branchId)
                    require(agents.isNotEmpty()) { "No agents found for this branch" }
                    val byEmp = agents.associateBy { it.employeeId.trim().lowercase() }
                    val byName = agents.groupBy { it.name.trim().lowercase() }
                    val catalog = SupabaseClaimsReader.fetchClaimCategories()
                    require(catalog.isNotEmpty()) { "Could not load categories" }
                    val groupOf = catalog.associate { it.name.trim().lowercase() to it.group.trim().lowercase() }
                    val valid = mutableListOf<ImportRow>()
                    val invalid = mutableListOf<String>()
                    grid.drop(1).forEachIndexed { i, cells ->
                        val line = i + 2
                        fun cell(idx: Int): String = cells.getOrElse(idx) { "" }.trim()
                        try {
                            val millis = parseDate(cell(cDate)) ?: throw IllegalArgumentException("bad date '${cell(cDate)}'")
                            val empId = cell(cEmpId)
                            val agent = byEmp[empId.lowercase()]
                                ?: throw IllegalArgumentException("unknown Agent ID '$empId'")
                            val rawCat = cell(cCat)
                            val cat = catalog.firstOrNull { it.name.equals(rawCat, ignoreCase = true) }?.name
                                ?: throw IllegalArgumentException("unknown category '$rawCat'")
                            val appr = parseAmount(cell(cAppr)) ?: throw IllegalArgumentException("bad approved '${cell(cAppr)}'")
                            val stl = parseAmount(cell(cSet)) ?: throw IllegalArgumentException("bad settled '${cell(cSet)}'")
                            if (cReq >= 0 && cell(cReq).isNotBlank()) {
                                val req = parseAmount(cell(cReq))
                                    ?: throw IllegalArgumentException("bad requested '${cell(cReq)}'")
                                if (req != appr) throw IllegalArgumentException("requested ($req) must equal approved ($appr)")
                            }
                            if (stl > appr) throw IllegalArgumentException("settled ($stl) exceeds approved ($appr)")
                            val cid = cell(cCid)
                            if (cid.isBlank()) throw IllegalArgumentException("Consignment/Merchant required")
                            val from = if (cFrom >= 0) cell(cFrom) else ""
                            val to = if (cTo >= 0) cell(cTo) else ""
                            val vehicle = if (cVehicle >= 0) cell(cVehicle) else ""
                            if (groupOf[cat.lowercase()] == "conveyance" && (from.isBlank() || to.isBlank() || vehicle.isBlank())) {
                                throw IllegalArgumentException("From/To/Vehicle required for $cat")
                            }
                            // Quantities mirror the request form: Pickup carries the
                            // pickup count, other conveyance is exactly 1 attempt /
                            // 1 success per claim, non-conveyance carries none.
                            // Sheet may override with Attempt/Success columns.
                            val isPickup = cat.equals(PC_CATEGORY_PICKUP, ignoreCase = true)
                            val isConveyance = groupOf[cat.lowercase()] == "conveyance"
                            fun parseQty(raw: String, what: String): Int? {
                                if (raw.isBlank()) return null
                                return raw.toIntOrNull()?.takeIf { it >= 0 }
                                    ?: throw IllegalArgumentException("bad $what '$raw'")
                            }
                            val attempted = parseQty(if (cAttempt >= 0) cell(cAttempt) else "", "attempt")
                                ?: if (isConveyance) 1 else 0
                            val succeeded = parseQty(if (cSuccess >= 0) cell(cSuccess) else "", "success")
                                ?: if (isConveyance) attempted else 0
                            if (succeeded > attempted) {
                                throw IllegalArgumentException("success ($succeeded) exceeds attempt ($attempted)")
                            }
                            valid.add(
                                ImportRow(
                                    lineNo = line, dateMillis = millis, dateIso = isoDate(millis),
                                    fromArea = officeWord(from), toArea = officeWord(to),
                                    vehicle = vehicle,
                                    agent = agent, category = cat,
                                    cidOrMerchant = cid,
                                    storeId = if (cStore >= 0) cell(cStore) else "",
                                    approved = appr, settled = stl,
                                    attempted = attempted, succeeded = succeeded,
                                    pickupCount = if (isPickup) attempted else 0,
                                )
                            )
                        } catch (e: Exception) {
                            invalid.add("Row $line: ${e.message}")
                        }
                    }
                    valid to invalid
                }
            }.getOrElse {
                v.findViewById<TextView>(R.id.tvPcBulkPreview).text = ""
                toast("Validation failed: ${it.message}")
                return@launch
            }
            validRows = result.first
            invalidLines = result.second
            val preview = buildString {
                append("${validRows.size} valid, ${invalidLines.size} invalid")
                invalidLines.take(12).forEach { append("\n• $it") }
                if (invalidLines.size > 12) append("\n• …and ${invalidLines.size - 12} more")
                if (invalidLines.isNotEmpty()) append("\n\nFix the sheet and pick again — submit stays locked until every row is valid.")
            }
            v.findViewById<TextView>(R.id.tvPcBulkPreview).text = preview
            // Strict: ANY invalid row locks submit — no partial submits.
            v.findViewById<Button>(R.id.btnPcBulkSubmit).isEnabled =
                validRows.isNotEmpty() && invalidLines.isEmpty() && !submitting
            v.findViewById<Button>(R.id.btnPcBulkSubmit).text = when {
                validRows.isEmpty() -> "Submit valid rows"
                invalidLines.isNotEmpty() -> "Fix ${invalidLines.size} invalid rows first"
                else -> "Submit ${validRows.size} rows"
            }
        }
    }

    private fun officeWord(value: String): String =
        if (value.trim().equals("office", ignoreCase = true)) "Office" else value

    /** "26-Aug-26" / ISO / serial → noon-local millis, else null. */
    private fun parseDate(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { serial ->
            if (serial > 20000 && serial < 80000) {
                val base = BdTime.cal().apply { set(1899, Calendar.DECEMBER, 30, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
                base.add(Calendar.DAY_OF_YEAR, serial.toInt())
                return base.timeInMillis
            }
        }
        val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(s)
        if (iso != null) {
            return calAtNoon(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt())
        }
        val txt = Regex("""^(\d{1,2})-([A-Za-z]{3,9})-(\d{2}|\d{4})$""").matchEntire(s)
        if (txt != null) {
            val mon = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                .indexOf(txt.groupValues[2].take(3).lowercase())
            if (mon < 0) return null
            var year = txt.groupValues[3].toInt()
            if (year < 100) year += 2000
            return calAtNoon(year, mon, txt.groupValues[1].toInt())
        }
        return null
    }

    private fun calAtNoon(year: Int, month0: Int, day: Int): Long =
        BdTime.cal().apply {
            set(year, month0, day, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val isoUtc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun isoDate(millis: Long): String = isoUtc.format(java.util.Date(millis))

    private fun parseAmount(raw: String): Double? {
        val s = raw.trim().replace(",", "").replace("৳", "").replace("Tk", "", ignoreCase = true).trim()
        if (s.isEmpty()) return null
        return s.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    private fun confirmSubmit() {
        if (validRows.isEmpty() || invalidLines.isNotEmpty() || submitting) return
        val total = validRows.sumOf { it.settled }
        AlertDialog.Builder(requireContext())
            .setTitle("Submit ${validRows.size} claims?")
            .setMessage(
                "All ${validRows.size} rows are valid. Each is saved DIRECTLY as settled " +
                    "for its agent with the sheet's approve/settled amounts (total settled ৳${total.toLong()}), " +
                    "dated exactly as its expense Date — no approve flow. Wallet updates automatically."
            )
            .setPositiveButton("Submit") { _, _ -> runSubmit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One server call for the whole batch (claims-bulk-import Edge): ids and
     *  date-wise codes are expense-based, submit date = expense date, rows
     *  land directly as settled. */
    private fun runSubmit() {
        val v = view ?: return
        submitting = true
        v.findViewById<Button>(R.id.btnPcBulkSubmit).isEnabled = false
        val bar = v.findViewById<ProgressBar>(R.id.pbPcBulkProgress)
        val status = v.findViewById<TextView>(R.id.tvPcBulkStatus)
        bar.isVisible = true
        bar.isIndeterminate = true
        val batch = "bulk-$branchId-${System.currentTimeMillis()}"
        val codeDate = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
        lifecycleScope.launch {
            status.text = "Submitting ${validRows.size} rows…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                        ?: error("Not signed in")
                    val token = user.getIdToken(false).await().token
                        ?: error("Could not get an ID token")
                    val rowsJson = org.json.JSONArray()
                    validRows.forEachIndexed { i, row ->
                        val id = ClaimsRepository.claimId(row.dateMillis + i)
                        rowsJson.put(
                            org.json.JSONObject()
                                .put("id", id)
                                .put("claim_code", "CLM-${codeDate.format(java.util.Date(row.dateMillis))}-${id.takeLast(5)}")
                                .put("requester_system_id", row.agent.systemId)
                                .put("category", row.category)
                                .put("purpose", row.cidOrMerchant.ifBlank { "${row.fromArea} to ${row.toArea}".trim() })
                                .put("vehicle", row.vehicle)
                                .put("from_area", row.fromArea)
                                .put("to_area", row.toArea)
                                .put("cid_or_merchant", row.cidOrMerchant)
                                .put("store_id", row.storeId)
                                .put("requested_amount", row.approved)
                                .put("approved_amount", row.approved)
                                .put("settled_amount", row.settled)
                                .put("attempted_qty", row.attempted)
                                .put("succeeded_qty", row.succeeded)
                                .put("pickup_count", row.pickupCount)
                                .put("requested_at", row.dateIso)
                                .put("client_submit_id", "$batch-$i")
                        )
                    }
                    val payload = org.json.JSONObject()
                        .put("action", "bulk_import")
                        .put("branch_id", branchId)
                        .put("batch", batch)
                        .put("rows", rowsJson)
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("${SupabaseConfig.PROJECT_URL}/functions/v1/claims-bulk-import")
                        .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) error("Import failed (HTTP ${resp.code}): ${text.take(500)}")
                        org.json.JSONObject(text)
                    }
                }
            }
            submitting = false
            bar.isVisible = false
            bar.isIndeterminate = false
            result.onSuccess { json ->
                val done = json.optInt("inserted", 0)
                val dups = json.optInt("duplicates", 0)
                val fails = json.optJSONArray("failed")
                val msgs = mutableListOf<String>()
                if (fails != null) {
                    for (i in 0 until fails.length()) {
                        val f = fails.optJSONObject(i) ?: continue
                        val line = validRows.getOrNull(f.optInt("index", -1))?.lineNo ?: "?"
                        msgs.add("Row $line (${f.optString("code")}): ${f.optString("error")}")
                    }
                }
                status.text = "Done: $done settled, $dups duplicates, ${msgs.size} failed"
                if (msgs.isNotEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Failures (${msgs.size})")
                        .setMessage(msgs.take(20).joinToString("\n"))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    toast("✅ $done claims settled" + if (dups > 0) " ($dups already existed)" else "")
                }
            }.onFailure {
                status.text = "Submit failed"
                toast("Submit failed: ${it.message}")
            }
            v.findViewById<Button>(R.id.btnPcBulkSubmit).isEnabled =
                validRows.isNotEmpty() && invalidLines.isEmpty() && !submitting
        }
    }

    // ── Sample file ────────────────────────────────────────────────────────

    private var pendingSampleDownload: (() -> Unit)? = null
    private val samplePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingSampleDownload?.invoke()
        else toast("Storage permission denied — cannot save sample")
        pendingSampleDownload = null
    }

    /** Writes a 3-row example sheet (same columns the importer reads) and
     *  saves it to Downloads — fill it and pick it back to import. */
    private fun downloadSample() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ctx = requireContext()
                    val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                    val file = java.io.File(dir, "bulk_import_sample.xlsx")
                    val headers = listOf("Date", "From", "To", "Vehicle", "Invoice", "Agent ID", "Agent Name", "Type", "Consignment/Merchant", "LOT ID", "Requested", "Approved", "Settled", "Attempt", "Success", "Status")
                    val rows = listOf(
                        listOf<Any>("25-Sep-26", "Kanchpur-Gongapur", "Office", "Auto", "", "S 35580", "Abdul Mannan Bepari", "Pickup", "Aureli BD", "", 150, 150, 150, 1, 1, ""),
                        listOf<Any>("25-Sep-26", "Office", "Kewdhala", "Auto", "", "FDA 9317", "Amir Hosen", "Bulk Delivery", "DW250926ABC123", "", 15, 15, 15, 1, 1, ""),
                        listOf<Any>("25-Sep-26", "", "", "", "", "M 1703", "Alif Mia", "Internet Bill", "September'26", "", 1000, 1000, 1000, 0, 0, ""),
                    )
                    val widths = listOf(11, 20, 20, 12, 20, 12, 24, 24, 22, 12, 11, 11, 11, 11, 11, 16)
                    CashExportWriter.writeXlsx(file, "Sample", headers, rows, widths)
                    file
                }
            }.onSuccess { file ->
                saveFileToDownloads(file, "bulk_import_sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            }.onFailure {
                toast("Sample failed: ${it.message}")
            }
        }
    }

    private fun saveFileToDownloads(file: java.io.File, displayName: String, mimeType: String) {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSampleDownload = { saveFileToDownloads(file, displayName, mimeType) }
            samplePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        runCatching {
            val resolver = ctx.contentResolver
            val uri: android.net.Uri? =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } else {
                    @Suppress("DEPRECATION")
                    android.net.Uri.fromFile(
                        java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            displayName,
                        )
                    )
                }
            if (uri == null) throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                ?: throw IllegalStateException("Could not write file")
        }.onSuccess {
            toast("✅ Sample saved to Downloads — fill it and pick it back")
        }.onFailure { toast("Download failed: ${it.message}") }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashBulkImportFragment {
            val f = PettyCashBulkImportFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }
}
