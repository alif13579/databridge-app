package com.cloudx.databridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CC Dashboard data layer — mirrors the extension popup's Dashboard tab
 * (Hold Validation + Team Performance) against the same Supabase backend.
 *
 * Reads go through free PostgREST (same shape the validations Edge Function
 * used to return) instead of invoking the Edge report action. Dhaka
 * (Asia/Dhaka) is the ops zone for every date grouping/display, matching
 * the extension's BD_DATE_PARTS behavior.
 */
object CcDashboardRepository {

    private const val TAG = "CcDashboard"
    private fun String.encodeParam(): String = java.net.URLEncoder.encode(this, "UTF-8")
    private val opsZone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val dateKeyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val ddMmYyyyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val mmDdYyyyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
    private val hhMmFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(opsZone)

    // ── Raw validation row ───────────────────────────────────────────────

    data class VRow(val raw: JSONObject) {
        val consignment: String get() = raw.optString("consignment")
        val branchId: String get() = raw.optString("branch_id")
        val assignedTo: String get() = raw.optString("assigned_to_system_id")
        val authorSys: String get() = raw.optString("author_system_id")
        val source: String get() = raw.optString("source")
        val status: String get() = raw.optString("remarks_status")
        val remarks: String get() = raw.optString("remarks")
        val note: String get() = raw.optString("note")
        val phone: String get() = raw.optString("customer_phone")
        val consignmentStatus: String get() = raw.optString("consignment_status")
        val assignedName: String get() = raw.optJSONObject("assigned")?.optString("name").orEmpty()
        val assignedEmp: String get() = raw.optJSONObject("assigned")?.optString("employee_id").orEmpty()
        val authorName: String get() = raw.optJSONObject("author")?.optString("name").orEmpty()
        val authorEmp: String get() = raw.optJSONObject("author")?.optString("employee_id").orEmpty()
        val createdMs: Long get() = SupabaseRemarkValidationWriter.parseDbTimestampMillis(raw.optString("created_at"))
        val isWorker: Boolean get() = source == "WORKER"
        val isCc: Boolean get() = source == "CC"
        val isVerifyRequest: Boolean get() = isWorker && status.trim().uppercase() == "VERIFY_REQUEST"
    }

    // ── Fetch ────────────────────────────────────────────────────────────

    /** All validation rows for one branch in [startIso, endIso), paged. */
    suspend fun fetchReportRows(branchId: String, startIso: String, endIso: String): List<VRow> =
        withContext(Dispatchers.IO) {
            val token = SupabaseClientManager.getAccessToken() ?: return@withContext emptyList()
            val rows = mutableListOf<VRow>()
            var offset = 0
            while (true) {
                val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/validations" +
                    "?select=*&branch_id=eq.${branchId.encodeParam()}" +
                    "&created_at=gte.${startIso.encodeParam()}&created_at=lt.${endIso.encodeParam()}" +
                    "&order=created_at.asc&limit=1000&offset=$offset"
                val text = try {
                    SupabaseClientManager.httpClient.newCall(
                        Request.Builder().url(url)
                            .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("Accept", "application/json").get().build()
                    ).execute().use { it.body?.string().orEmpty() }
                } catch (e: Exception) {
                    Log.e(TAG, "report fetch failed: ${e.message}")
                    throw e
                }
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) rows.add(VRow(arr.getJSONObject(i)))
                if (arr.length() < 1000) break
                offset += 1000
            }
            rows
        }

    /** systemId → (name, employeeId), chunked like the extension. */
    suspend fun fetchUserNames(systemIds: Set<String>): Map<String, Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val out = mutableMapOf<String, Pair<String, String>>()
            val ids = systemIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext out
            val token = SupabaseClientManager.getAccessToken() ?: return@withContext out
            ids.chunked(200).forEach { chunk ->
                val url = "${SupabaseConfig.PROJECT_URL}/rest/v1/users" +
                    "?select=system_id,name,employee_id&system_id=in.(${chunk.joinToString(",")})&limit=1000"
                runCatching {
                    SupabaseClientManager.httpClient.newCall(
                        Request.Builder().url(url)
                            .addHeader("apikey", SupabaseConfig.PUBLISHABLE_KEY)
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("Accept", "application/json").get().build()
                    ).execute().use { resp ->
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            out[o.optString("system_id")] = o.optString("name") to o.optString("employee_id")
                        }
                    }
                }
            }
            out
        }

    // ── Dhaka date helpers ───────────────────────────────────────────────

    fun dateKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(opsZone).toLocalDate().format(dateKeyFmt)
    fun ddMmYyyy(dateKey: String): String = runCatching {
        LocalDate.parse(dateKey, dateKeyFmt).format(ddMmYyyyFmt)
    }.getOrDefault(dateKey)
    fun mmDdYyyy(dateKey: String): String = runCatching {
        LocalDate.parse(dateKey, dateKeyFmt).format(mmDdYyyyFmt)
    }.getOrDefault(dateKey)
    fun hhMm(ms: Long): String = hhMmFmt.format(Instant.ofEpochMilli(ms))
    fun localDateOf(dateKey: String): LocalDate = LocalDate.parse(dateKey, dateKeyFmt)

    /** "Name (emp)" when both distinct, else whichever exists, else "—". */
    fun who(name: String, emp: String, sys: String): String {
        val n = name.trim()
        val e = emp.trim()
        return when {
            n.isNotBlank() && e.isNotBlank() && n != e -> "$n ($e)"
            n.isNotBlank() -> n
            e.isNotBlank() -> e
            sys.isNotBlank() -> sys
            else -> "—"
        }
    }

    // ── Hold Validation: summary ─────────────────────────────────────────

    data class HvDay(
        val dateKey: String,
        val dateLabel: String,
        val workerRemark: String, val workerStatus: String, val workerTime: String, val workerWho: String,
        val hasCc: Boolean,
        val ccRemark: String, val ccNote: String, val ccStatus: String, val ccTime: String, val ccWho: String,
    )

    data class HvCard(
        val dateKey: String, // last day
        val dateLabel: String, // single date or First → Last
        val branchId: String,
        val cId: String,
        val agentSys: String,
        val agentName: String, val agentWho: String, val agentEmpId: String,
        val customerPhone: String,
        val parcelStatus: String,
        val firstWorkerRemark: String, val firstWorkerStatus: String, val firstWorkerTime: String,
        val lastCcRemark: String, val lastCcNote: String, val lastCcStatus: String, val lastCcTime: String,
        val validatorName: String, val validatorWho: String, val validatorEmpId: String,
        val stillPending: Boolean,
        val days: List<HvDay>,
    )

    /** Summary cards: one per consignment over the whole range. Pending first, then newest. */
    fun buildSummaryCards(rows: List<VRow>, names: Map<String, Pair<String, String>>): List<HvCard> {
        fun nm(sys: String): Pair<String, String> = names[sys] ?: ("" to "")
        // groups keyed dateKey__consignment, keep only groups with a worker verify request
        val groups = rows.groupBy { "${dateKey(it.createdMs)}__${it.consignment}" }
            .filterValues { g -> g.any { it.isVerifyRequest } }
        val cards = mutableListOf<HvCard>()
        groups.values.forEach { g ->
            val byDay = g.groupBy { dateKey(it.createdMs) }
            val dayList = byDay.map { (dk, dayRows) ->
                val wv = dayRows.filter { it.isVerifyRequest }
                val cc = dayRows.filter { it.isCc }
                val fw = wv.minByOrNull { it.createdMs }
                val lc = cc.maxByOrNull { it.createdMs }
                val (wName, wEmp) = if (fw != null) nm(fw.authorSys) else ("" to "")
                val wWho = if (fw != null) who(
                    (wName.ifBlank { fw.authorName }), (wEmp.ifBlank { fw.authorEmp }), fw.authorSys) else ""
                val (cName, cEmp) = if (lc != null) nm(lc.authorSys) else ("" to "")
                val cWho = if (lc != null) who(
                    (cName.ifBlank { lc.authorName }), (cEmp.ifBlank { lc.authorEmp }), lc.authorSys) else ""
                HvDay(
                    dateKey = dk, dateLabel = ddMmYyyy(dk),
                    workerRemark = fw?.remarks.orEmpty(), workerStatus = fw?.status.orEmpty(),
                    workerTime = if (fw != null) hhMm(fw.createdMs) else "", workerWho = wWho,
                    hasCc = lc != null,
                    ccRemark = lc?.remarks.orEmpty(), ccNote = lc?.note.orEmpty(),
                    ccStatus = lc?.status.orEmpty(),
                    ccTime = if (lc != null) hhMm(lc.createdMs) else "", ccWho = cWho,
                )
            }.sortedBy { it.dateKey }
            if (dayList.isEmpty()) return@forEach
            val verifyAll = g.filter { it.isVerifyRequest }
            val ccAll = g.filter { it.isCc }
            val latestVerify = verifyAll.maxByOrNull { it.createdMs }!!
            val latestCc = ccAll.maxByOrNull { it.createdMs }
            val latest = g.maxByOrNull { it.createdMs }!!
            val stillPending = latestCc == null || latestVerify.createdMs > latestCc.createdMs
            val first = g.first()
            val (aName, aEmp) = nm(latest.assignedTo)
            val agentName = aName.ifBlank { latest.assignedName }
            val (vName, vEmp) = if (latestCc != null) nm(latestCc.authorSys) else ("" to "")
            val validatorName = vName.ifBlank { latestCc?.authorName.orEmpty() }
            val lastDay = dayList.last()
            val firstDay = dayList.first()
            cards.add(HvCard(
                dateKey = lastDay.dateKey,
                dateLabel = if (dayList.size == 1) lastDay.dateLabel
                    else "${firstDay.dateLabel} → ${lastDay.dateLabel}",
                branchId = first.branchId, cId = first.consignment,
                agentSys = latest.assignedTo,
                agentName = agentName.ifBlank { latest.assignedTo.ifBlank { "—" } },
                agentWho = who(agentName, aEmp.ifBlank { latest.assignedEmp }, latest.assignedTo),
                agentEmpId = aEmp.ifBlank { latest.assignedEmp },
                customerPhone = latest.phone,
                parcelStatus = latest.consignmentStatus.ifBlank { latestCc?.consignmentStatus.orEmpty() },
                firstWorkerRemark = lastDay.workerRemark, firstWorkerStatus = lastDay.workerStatus,
                firstWorkerTime = lastDay.workerTime,
                lastCcRemark = lastDay.ccRemark, lastCcNote = lastDay.ccNote,
                lastCcStatus = lastDay.ccStatus, lastCcTime = lastDay.ccTime,
                validatorName = validatorName,
                validatorWho = if ((latestCc?.authorSys).isNullOrBlank()) "" else who(
                    validatorName, vEmp.ifBlank { latestCc?.authorEmp.orEmpty() }, latestCc?.authorSys.orEmpty()),
                validatorEmpId = vEmp.ifBlank { latestCc?.authorEmp.orEmpty() },
                stillPending = stillPending,
                days = dayList,
            ))
        }
        return cards.sortedWith(compareByDescending<HvCard> { it.stillPending }.thenByDescending { it.dateKey })
    }

    // ── Hold Validation: details ─────────────────────────────────────────

    data class HvDetail(
        val dateKey: String, val dateLabel: String, val timeLabel: String,
        val branchId: String, val cId: String,
        val agentName: String, val agentSys: String,
        val authorName: String, val authorSys: String,
        val parcelStatus: String, val source: String,
        val remark: String, val note: String, val status: String,
    )

    /** Every raw row of valid groups, oldest first. */
    fun buildDetails(rows: List<VRow>, names: Map<String, Pair<String, String>>): List<HvDetail> {
        val groups = rows.groupBy { "${dateKey(it.createdMs)}__${it.consignment}" }
            .filterValues { g -> g.any { it.isVerifyRequest } }
        val out = mutableListOf<HvDetail>()
        groups.values.forEach { g ->
            g.sortedBy { it.createdMs }.forEach { r ->
                val (aName, _) = names[r.assignedTo] ?: ("" to "")
                val (auName, auEmp) = names[r.authorSys] ?: ("" to "")
                val agentName = (aName.ifBlank { r.assignedName }).ifBlank { r.assignedTo.ifBlank { "—" } }
                val authorName = (auName.ifBlank { r.authorName }).ifBlank {
                    (auEmp.ifBlank { r.authorEmp }).ifBlank { r.authorSys } }
                out.add(HvDetail(
                    dateKey = dateKey(r.createdMs), dateLabel = ddMmYyyy(dateKey(r.createdMs)),
                    timeLabel = hhMm(r.createdMs),
                    branchId = r.branchId, cId = r.consignment,
                    agentName = agentName, agentSys = r.assignedTo,
                    authorName = authorName, authorSys = r.authorSys,
                    parcelStatus = r.consignmentStatus, source = r.source,
                    remark = r.remarks, note = r.note, status = r.status,
                ))
            }
        }
        return out.sortedWith(compareBy({ it.dateKey }, { it.cId }, { it.timeLabel }))
    }

    // ── Team Performance ─────────────────────────────────────────────────

    private val deliveryFamily = setOf("delivered", "partial delivery", "partial", "paid return", "exchange")

    data class PerfParcel(
        val cId: String, val bucket: String, val latestAt: Long,
        val agent: String, val ccLabel: String, val nowStatus: String, val converted: Boolean,
        val reqDate: String, val reqCount: Int,
    )

    data class PerfTeamRow(
        val agentId: String, val agentName: String, val agentEmp: String,
        val total: Int, val deliveryRequest: Int, val holdVerified: Int, val returnVerified: Int, val other: Int,
    )

    data class PerfAgentRow(
        val agentId: String, val agentName: String, val agentEmp: String,
        val requested: Int, val validated: Int,
    )

    data class PerfSummary(
        val totalUnique: Int, val drUnique: Int, val drPct: Int,
        val converted: Int, val convertedPct: Int,
        val holdPct: Int, val returnPct: Int,
    )

    private fun bucketOf(status: String): String = when (status.trim().lowercase()) {
        "delivery_request" -> "delivery_request"
        "hold_verified" -> "hold_verified"
        "return_verified" -> "return_verified"
        else -> "other"
    }

    private fun inDeliveryFamily(status: String): Boolean =
        status.trim().lowercase().replace('_', ' ') in deliveryFamily

    fun buildTeamPerf(rows: List<VRow>, names: Map<String, Pair<String, String>>): Triple<PerfSummary, List<PerfTeamRow>, List<PerfParcel>> {
        val ccRows = rows.filter { it.isCc }
        // unique consignments + latest CC wins per consignment
        val byCid = ccRows.groupBy { it.consignment }
        val totalUnique = byCid.size
        val latestCcByCid = byCid.mapValues { (_, v) -> v.maxByOrNull { it.createdMs }!! }
        val buckets = latestCcByCid.values.groupingBy { bucketOf(it.status) }.eachCount()
        val drUnique = buckets["delivery_request"] ?: 0
        // converted: latest OVERALL row (any source) in delivery family
        val latestOverall = rows.groupBy { it.consignment }.mapValues { (_, v) -> v.maxByOrNull { it.createdMs }!! }
        val converted = latestCcByCid.keys.count { inDeliveryFamily(latestOverall[it]?.consignmentStatus.orEmpty()) }
        val summary = PerfSummary(
            totalUnique = totalUnique, drUnique = drUnique,
            drPct = pct(drUnique, totalUnique),
            converted = converted, convertedPct = pct(converted, drUnique),
            holdPct = pct(buckets["hold_verified"] ?: 0, totalUnique),
            returnPct = pct(buckets["return_verified"] ?: 0, totalUnique),
        )
        // team rows: dedupe (consignment, author) keep latest, group by author
        val deduped = ccRows.groupBy { "${it.consignment}__${it.authorSys}" }
            .mapValues { (_, v) -> v.maxByOrNull { it.createdMs }!! }.values
        val teamRows = deduped.groupBy { it.authorSys }.map { (aid, v) ->
            val (nm, emp) = names[aid] ?: (v.first().authorName to v.first().authorEmp)
            val b = v.groupingBy { bucketOf(it.status) }.eachCount()
            PerfTeamRow(aid, nm.ifBlank { aid }, emp, v.size,
                b["delivery_request"] ?: 0, b["hold_verified"] ?: 0,
                b["return_verified"] ?: 0, b["other"] ?: 0)
        }.sortedByDescending { it.total }
        // parcels for filtered view
        val parcels = byCid.map { (cid, v) ->
            val latestCc = latestCcByCid[cid]!!
            val overall = latestOverall[cid]!!
            val wRows = rows.filter { it.consignment == cid && it.isWorker }
            PerfParcel(cid, bucketOf(latestCc.status), latestCc.createdMs,
                (names[latestCc.authorSys]?.first ?: latestCc.authorName).ifBlank { latestCc.authorSys },
                latestCc.status, overall.consignmentStatus,
                inDeliveryFamily(overall.consignmentStatus),
                if (wRows.isNotEmpty()) ddMmYyyy(dateKey(wRows.minByOrNull { it.createdMs }!!.createdMs)) else "",
                wRows.size)
        }.sortedByDescending { it.latestAt }
        return Triple(summary, teamRows, parcels)
    }

    fun buildAgentPerf(rows: List<VRow>, names: Map<String, Pair<String, String>>): Pair<List<PerfAgentRow>, List<PerfParcel>> {
        val byCid = rows.groupBy { it.consignment }
        // per consignment with ≥1 worker row
        val withWorker = byCid.filterValues { v -> v.any { it.isWorker } }
        val agentGroups = mutableMapOf<String, MutableList<String>>()
        val validated = mutableMapOf<String, Int>()
        withWorker.forEach { (cid, v) ->
            val latest = v.maxByOrNull { it.createdMs }!!
            val aid = latest.assignedTo.ifBlank { v.first().assignedTo }
            agentGroups.getOrPut(aid) { mutableListOf() }.add(cid)
            if (latest.isCc) validated[aid] = (validated[aid] ?: 0) + 1
        }
        val agentRows = agentGroups.map { (aid, cids) ->
            val sample = withWorker[cids.first()]!!.maxByOrNull { it.createdMs }!!
            val (nm, emp) = names[aid] ?: (sample.assignedName to sample.assignedEmp)
            PerfAgentRow(aid, nm.ifBlank { aid }, emp, cids.size, validated[aid] ?: 0)
        }.sortedWith(compareByDescending<PerfAgentRow> { it.requested }.thenByDescending { it.validated })
        val latestOverall = rows.groupBy { it.consignment }.mapValues { (_, v) -> v.maxByOrNull { it.createdMs }!! }
        val parcels = withWorker.map { (cid, v) ->
            val latest = latestOverall[cid]!!
            val wFirst = v.filter { it.isWorker }.minByOrNull { it.createdMs }!!
            val ccCount = v.count { it.isCc }
            PerfParcel(cid, if (ccCount > 0) "delivery_request" else "other", latest.createdMs,
                (names[latest.assignedTo]?.first ?: latest.assignedName).ifBlank { latest.assignedTo },
                "${ccCount} CC", latest.consignmentStatus,
                inDeliveryFamily(latest.consignmentStatus),
                ddMmYyyy(dateKey(wFirst.createdMs)), v.count { it.isWorker })
        }.sortedByDescending { it.latestAt }
        return agentRows to parcels
    }

    private fun pct(part: Int, whole: Int): Int =
        if (whole <= 0) 0 else Math.round(part * 100f / whole)

    // ── CSV ──────────────────────────────────────────────────────────────

    fun csvCell(value: String): String {
        val s = value.orEmpty()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    fun summaryCsv(cards: List<HvCard>, branchNameOf: (String) -> String): String {
        val sb = StringBuilder()
        sb.appendLine(listOf("Date", "Branch", "Consignment ID", "Agent Name", "Agent System ID",
            "Parcel Status", "Validator Name", "Validator Employee ID", "First Worker Remark",
            "First Worker Remark Status", "Last CC Remark", "Last CC Note", "Last CC Remark Status",
            "Validation Status").joinToString(","))
        cards.forEach { c ->
            sb.appendLine(listOf(
                mmDdYyyy(c.dateKey), branchNameOf(c.branchId), c.cId, c.agentName, c.agentSys,
                c.parcelStatus, c.validatorName, c.validatorEmpId, c.firstWorkerRemark, c.firstWorkerStatus,
                c.lastCcRemark, c.lastCcNote, c.lastCcStatus, if (c.stillPending) "Pending" else "Validated",
            ).joinToString(",") { csvCell(it) })
        }
        return sb.toString()
    }

    fun detailsCsv(rows: List<HvDetail>, branchNameOf: (String) -> String): String {
        val sb = StringBuilder()
        sb.appendLine(listOf("Date", "Time", "Branch", "Consignment ID", "Agent Name", "Agent System ID",
            "Parcel Status", "Author Name", "Author System ID", "Source", "Remark", "Note",
            "Remark Status").joinToString(","))
        rows.forEach { r ->
            sb.appendLine(listOf(
                mmDdYyyy(r.dateKey), r.timeLabel, branchNameOf(r.branchId), r.cId, r.agentName,
                r.agentSys, r.parcelStatus, r.authorName, r.authorSys,
                if (r.source == "WORKER") "Worker" else "CC",
                r.remark, r.note, r.status,
            ).joinToString(",") { csvCell(it) })
        }
        return sb.toString()
    }
}
