package com.cloudx.databridge

import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * ConfigSheetFragment's ManagePanel (Overview/Columns/Sync tabs for an already-connected
 * sheet) and Firebase persistence layer (load all connections on fragment start, save a
 * new/edited connection, delete one).
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment for the same reason as
 * the other Config-Sheet module splits — activeManageTab, connections, branches, etc.
 * are read from other parts of the fragment too (branch select screen, wizard steps).
 */
// ── ManagePanel ───────────────────────────────────────────────────
internal fun ConfigSheetFragment.renderManagePanel() {
    val conn = activeConn() ?: return
    val connNickname = conn.nickname.ifBlank { conn.sheetName }
    tvManageBranch?.text = "${branchLabel(activeBranch)}  ·  $connNickname"

    // Branch switcher removed — Manage shows only the specific sheet/branch user navigated to
    spinnerManageBranch?.visibility = View.GONE

    activeManageTab = "overview"
    renderManageTabs()
    renderSyncTab(conn)

    tvOvSheet?.text = conn.sheetName
    tvOvTab?.text   = conn.tabName
    tvOvCols?.text  = "${conn.columns.firstOrNull() ?: "A"}–${conn.columns.lastOrNull() ?: "J"} (${conn.columns.size}টি)"
    tvColPreviewMgr?.text = "${conn.columns.firstOrNull() ?: "A"} → ${conn.columns.lastOrNull() ?: "J"}  (${conn.columns.size} columns)"
    if (activeManageTab == "columns") fetchManageColPreview()
}

internal fun ConfigSheetFragment.renderManageTabs() {
    val red  = android.graphics.Color.parseColor("#E8380D")
    val grey = context!!.getColor(R.color.theme_text_secondary)
    tabOverview?.setTextColor(if (activeManageTab == "overview") red else grey)
    tabColumns?.setTextColor (if (activeManageTab == "columns")  red else grey)
    tabSync?.setTextColor    (if (activeManageTab == "sync")     red else grey)
    indOverview?.visibility = if (activeManageTab == "overview") View.VISIBLE else View.INVISIBLE
    indColumns?.visibility  = if (activeManageTab == "columns")  View.VISIBLE else View.INVISIBLE
    indSync?.visibility     = if (activeManageTab == "sync")     View.VISIBLE else View.INVISIBLE
    cardOverview?.visibility = if (activeManageTab == "overview") View.VISIBLE else View.GONE
    cardColumns?.visibility  = if (activeManageTab == "columns")  View.VISIBLE else View.GONE
    cardSync?.visibility     = if (activeManageTab == "sync")     View.VISIBLE else View.GONE
}

internal fun ConfigSheetFragment.handleDisconnect() {
    val branch = activeBranch
    val connId = activeConnectionId
    connections[branch]?.removeAll { it.connectionId == connId }
    if (connections[branch].isNullOrEmpty()) connections.remove(branch)
    deleteFromFirebase(branch, connId)
    toast("🗑 Sheet disconnected")
    screen = ConfigScreen.BRANCH_SELECT
    render()
}

// ── Firebase ──────────────────────────────────────────────────────
internal fun ConfigSheetFragment.activeConn(): SheetConn? =
    connections[activeBranch]?.find { it.connectionId == activeConnectionId }
        ?: connections[activeBranch]?.firstOrNull()

internal fun ConfigSheetFragment.updateActiveConn(updated: SheetConn) {
    val list = connections.getOrPut(updated.branchId) { mutableListOf() }
    val idx = list.indexOfFirst { it.connectionId == updated.connectionId }
    if (idx >= 0) list[idx] = updated else list.add(updated)
}

internal fun ConfigSheetFragment.loadFromFirebase() {
    val owner = viewLifecycleOwnerLiveData.value ?: return
    owner.lifecycleScope.launch {
        setBusy(true, "Loading...")
        try {
            val uid = auth.currentUser?.uid.orEmpty()
            if (uid.isBlank()) {
                branches = emptyList()
                branchInfos = emptyMap()
                connections.clear()
            } else {
                val branchIdsPath = "users/$uid/profile/company_info/branch_ids"
                val userSnap = db.reference.child(branchIdsPath).get().await()
                val assignedBranchIds = readBranchIds(userSnap)

                val infos = mutableMapOf<String, BranchInfo>()
                assignedBranchIds.forEach { id ->
                    val branchPath = "branches/$id"
                    val b = db.reference.child(branchPath).get().await()
                    val name = b.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: id
                    val code = b.child("branch_code").getValue(String::class.java).orEmpty()
                    val address = b.child("address").getValue(String::class.java).orEmpty()
                    val type = b.child("branch_type").getValue(String::class.java).orEmpty()
                    val status = b.child("status").getValue(String::class.java).orEmpty()
                    infos[id] = BranchInfo(id, name, code, address, type, status)
                }

                branches = assignedBranchIds
                branchInfos = infos
                activeBranch = when {
                    branches.size == 1 -> branches.first()
                    branches.contains(activeBranch) -> activeBranch
                    else -> ""
                }
                connections.clear()

                val sheetSnap = db.reference.child("config/sheets").get().await()
                // Note: load from connections/{push_id} new structure
                sheetSnap.children.forEach { bs ->
                    val branchId = bs.key ?: return@forEach
                    if (!branches.contains(branchId)) return@forEach
                    val list = mutableListOf<SheetConn>()
                    bs.child("connections").children.forEach { connSnap ->
                        val connId    = connSnap.key ?: return@forEach
                        val sheetId   = connSnap.child("sheetId")  .getValue(String::class.java) ?: return@forEach
                        val sheetName = connSnap.child("sheetName").getValue(String::class.java) ?: ""
                        val tabName   = connSnap.child("tabName")  .getValue(String::class.java) ?: ""
                        val nickname  = connSnap.child("nickname") .getValue(String::class.java) ?: ""
                        val colS      = connSnap.child("colStart") .getValue(Int::class.java)    ?: 1
                        val colE      = connSnap.child("colEnd")   .getValue(Int::class.java)    ?: 10
                        val email     = connSnap.child("googleEmail").getValue(String::class.java) ?: ""
                        val by        = connSnap.child("connectedBy").getValue(String::class.java) ?: ""
                        val at        = connSnap.child("connectedAt").getValue(Long::class.java)   ?: 0L
                        val sRow      = connSnap.child("startRow")       .getValue(Int::class.java)
                        val eRow      = connSnap.child("endRow")         .getValue(Int::class.java)
                        val autoSync  = connSnap.child("autoSync")       .getValue(Boolean::class.java) ?: false
                        val interval  = connSnap.child("syncIntervalMin").getValue(Int::class.java)    ?: 30
                        @Suppress("UNCHECKED_CAST")
                        val colMap: Map<String, ColMapping> = connSnap.child("columnMapping").children.associate { fieldSnap ->
                            val k = fieldSnap.key ?: ""
                            val v = fieldSnap.value
                            val cm = when (v) {
                                is Map<*, *> -> ColMapping(
                                    col    = v["col"]?.toString() ?: "",
                                    header = v["header"]?.toString() ?: ""
                                )
                                is String -> ColMapping(col = v, header = "") // legacy
                                else -> ColMapping()
                            }
                            k to cm
                        }
                        val objMapRaw = connSnap.child("objectColumnMapping").children.associate { fieldSnap ->
                            fieldSnap.key.orEmpty() to ObjectColMapping(
                                keyCol      = fieldSnap.child("keyCol").getValue(String::class.java)
                                    ?: fieldSnap.child("key").getValue(String::class.java) ?: "",
                                keyHeader   = fieldSnap.child("keyHeader").getValue(String::class.java) ?: "",
                                valueCol    = fieldSnap.child("valueCol").getValue(String::class.java)
                                    ?: fieldSnap.child("value").getValue(String::class.java) ?: "",
                                valueHeader = fieldSnap.child("valueHeader").getValue(String::class.java) ?: "",
                            )
                        }.filterKeys { it.isNotBlank() }
                        val tgtNode    = connSnap.child("targetNode").getValue(String::class.java) ?: "courier/consignments"
                        val pkField    = connSnap.child("primaryKeyField").getValue(String::class.java) ?: ""
                        val pkParts    = connSnap.child("primaryKeyParts").children.mapNotNull { partSnap ->
                            val t = partSnap.child("type").getValue(String::class.java) ?: return@mapNotNull null
                            val v = partSnap.child("value").getValue(String::class.java) ?: ""
                            val h = partSnap.child("header").getValue(String::class.java) ?: ""
                            PkPart(t, v, h)
                        }
                        list.add(SheetConn(connId, nickname, branchId, sheetId, sheetName, tabName, colS, colE, sRow, eRow, autoSync, interval, email, by, at, colMap, objMapRaw, pkField, tgtNode, pkParts))
                    }
                    if (list.isNotEmpty()) connections[branchId] = list
                }
            }
        } catch (e: Exception) {
            Log.e("ConfigSheet", "Failed to load sheet config", e)
            toast("Sheet config load failed")
        } finally {
            if (isAdded) {
                // Always show BRANCH_SELECT — user chooses which sheet to manage
                screen = ConfigScreen.BRANCH_SELECT
                render()
                setBusy(false)
            }
        }
    }
}

internal fun ConfigSheetFragment.readBranchIds(snap: com.google.firebase.database.DataSnapshot): List<String> {
    if (!snap.exists()) return emptyList()
    return when (val raw = snap.value) {
        is String -> listOf(raw.trim()).filter { it.isNotBlank() }
        is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { v -> v.isNotBlank() } }
        is Map<*, *> -> raw.mapNotNull { (key, value) ->
            when (value) {
                is String -> value.trim().takeIf { it.isNotBlank() }
                false, null -> null
                else -> key?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        else -> snap.children.mapNotNull { child ->
            child.value?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: child.key?.trim()?.takeIf { it.isNotBlank() }
        }
    }.distinct()
}

internal suspend fun ConfigSheetFragment.saveToFirebase(conn: SheetConn): Boolean {
    return try {
        val data = mapOf(
            "nickname"        to conn.nickname,
            "sheetId"         to conn.sheetId,
            "sheetName"       to conn.sheetName,
            "tabName"         to conn.tabName,
            "colStart"        to conn.colStart,
            "colEnd"          to conn.colEnd,
            "startRow"        to (conn.startRow ?: 1),
            "endRow"          to (conn.endRow   ?: 0),
            "autoSync"        to conn.autoSync,
            "syncIntervalMin" to conn.syncIntervalMin,
            "googleEmail"     to conn.googleEmail,
            "connectedBy"     to conn.connectedBy,
            "connectedAt"     to conn.connectedAt,
            "columnMapping"   to conn.columnMapping.mapValues { (_, cm) ->
                mapOf("col" to cm.col, "header" to cm.header)
            },
            "objectColumnMapping" to conn.objectColumnMapping.mapValues { (_, ocm) ->
                mapOf(
                    "keyCol"      to ocm.keyCol,
                    "keyHeader"   to ocm.keyHeader,
                    "valueCol"    to ocm.valueCol,
                    "valueHeader" to ocm.valueHeader,
                )
            },
            "primaryKeyField" to conn.primaryKeyField,
            "primaryKeyParts" to conn.primaryKeyParts.map { part ->
                mapOf("type" to part.type, "value" to part.value, "header" to part.header)
            },
            "targetNode"      to conn.targetNode,
        )
        val basePath = "config/sheets/${conn.branchId}/connections"
        val connId = conn.connectionId.ifBlank { db.reference.child(basePath).push().key ?: return false }
        db.reference.child("$basePath/$connId").setValue(data).await()
        db.reference.child("config/sheets/${conn.branchId}/history").push()
            .setValue(data + mapOf("action" to "connected", "connectionId" to connId)).await()
        true
    } catch (e: Exception) {
        FirebaseErrorLogger.log(
            screen = "ConfigSheetFragment", action = "save_connection",
            errorMessage = e.message ?: "unknown",
            extra = mapOf("branchId" to conn.branchId, "connectionId" to conn.connectionId)
        )
        false
    }
}

internal fun ConfigSheetFragment.deleteFromFirebase(branchId: String, connectionId: String) {
    val owner = viewLifecycleOwnerLiveData.value ?: return
    owner.lifecycleScope.launch {
        try {
            db.reference.child("config/sheets/$branchId/connections/$connectionId").removeValue().await()
            db.reference.child("config/sheets/$branchId/history").push().setValue(mapOf(
                "action"         to "disconnected",
                "connectionId"   to connectionId,
                "disconnectedBy" to (auth.currentUser?.uid ?: ""),
                "disconnectedAt" to System.currentTimeMillis(),
            )).await()
        } catch (_: Exception) {}
    }
}

