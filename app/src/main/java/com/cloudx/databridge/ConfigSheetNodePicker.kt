package com.cloudx.databridge

import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Gravity
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ConfigSheetFragment's Firebase node picker — Step "Target Node" of the Sheet Connect
 * wizard. Lets the user drill into courier/{path} one level at a time (a dropdown per
 * depth), or fall back to a tree-preview + confirm box once a level has too many
 * children to show as a dropdown (see MAX_DRILLABLE_CHILDREN on the fragment), or
 * create a brand-new node via "+ Create New".
 *
 * Extracted from ConfigSheetFragment.kt as part of breaking that ~4500-line file into
 * modules. Written as extension functions on ConfigSheetFragment (not a separate class
 * holding its own state) because all of this section's state — nodePickerPath,
 * nodePickerRevealedDepth, nodeMappingConfirmed, nodeChildrenCache, knownNodePaths,
 * courierChildNodes, nodePreviewData, nodePreviewExpanded, and every view reference
 * (tvNodeBreadcrumb, containerNodeDropdowns, etc.) — is read or written from OTHER
 * sections of the wizard too (e.g. handleConnect() checks nodeMappingConfirmed before
 * allowing Save). Moving that state into a separate class would mean threading a
 * reference through every one of those call sites for no benefit; keeping it on the
 * fragment and moving only these functions here keeps behavior identical while
 * organizing the code.
 */

/**
 * Fetches the immediate child keys under "courier/{relativePath}" (shallow — just key
 * names, not full data). Cached per-path for the fragment's lifetime.
 */
internal suspend fun ConfigSheetFragment.fetchChildKeysAt(relativePath: String): List<String> {
    nodeChildrenCache[relativePath]?.let { return it }
    return try {
        val idToken = withContext(Dispatchers.IO) {
            try { auth.currentUser?.getIdToken(false)?.await()?.token } catch (_: Exception) { null }
        }
        val rootUrl = db.reference.root.toString().trimEnd('/')
        val authParam = idToken?.let { "&auth=$it" } ?: ""
        val fullPath = if (relativePath.isBlank()) "courier" else "courier/$relativePath"
        val url = "$rootUrl/$fullPath.json?shallow=true$authParam"
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }
        val liveKeys = if (body.isNullOrBlank() || body == "null") {
            emptyList()
        } else {
            val obj = org.json.JSONObject(body)
            obj.keys().asSequence().filter { it != NODE_META_KEY }.toList()
        }
        // Merge in any "known" (created-but-still-empty) direct children of this path
        // from the registry, so a node created via "+ Create New" doesn't disappear
        // from the dropdown just because it has no real data yet.
        val knownChildrenHere = loadKnownNodePaths().mapNotNull { known ->
            when {
                relativePath.isBlank() && !known.contains("/") -> known
                known.startsWith("$relativePath/") &&
                    !known.removePrefix("$relativePath/").contains("/") ->
                    known.removePrefix("$relativePath/")
                else -> null
            }
        }
        val keys = (liveKeys + knownChildrenHere).distinct().sorted()
        nodeChildrenCache[relativePath] = keys
        keys
    } catch (e: Exception) {
        Log.e("ConfigSheet", "❌ fetchChildKeysAt($relativePath) failed: ${e.message}", e)
        emptyList()
    }
}

/** Loads the config/known_nodes registry once per session (cached in [knownNodePaths]) —
 *  the list of "courier/..." suffixes created via "+ Create New" in the wizard, whether or
 *  not they hold real data yet. */
internal suspend fun ConfigSheetFragment.loadKnownNodePaths(): List<String> {
    knownNodePaths?.let { return it }
    val loaded = try {
        val snap = withContext(Dispatchers.IO) {
            db.reference.child("config/known_nodes").get().await()
        }
        snap.children.mapNotNull { it.getValue(String::class.java) }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }
    knownNodePaths = loaded
    return loaded
}

/** Registers [relativePath] (e.g. "run_routes" or "run_routes/delivery_run") in the
 *  config/known_nodes registry — called when the user confirms a brand-new node via
 *  "+ Create New" so it survives being empty and still shows up in the dropdown on the
 *  next render/session, without writing any placeholder into courier/ itself. Invalidates
 *  the parent path's cached child list so the new node appears immediately. */
internal suspend fun ConfigSheetFragment.registerKnownNode(relativePath: String) {
    if (relativePath.isBlank()) return
    val current = loadKnownNodePaths()
    if (relativePath in current) return // already known — nothing to do
    val sanitizedKey = relativePath.replace(Regex("[./#$\\[\\]]"), "_")
    // '/' is deliberately in that char class — without sanitizing it, Firebase's child()
    // would interpret a key containing '/' as a NESTED path (e.g. "run_routes/delivery_run"
    // would create config/known_nodes/run_routes/delivery_run as a tree, not a flat key),
    // breaking loadKnownNodePaths()'s assumption that each registry entry is a leaf String.
    try {
        withContext(Dispatchers.IO) {
            db.reference.child("config/known_nodes/$sanitizedKey").setValue(relativePath).await()
        }
    } catch (e: Exception) {
        Log.e("ConfigSheet", "❌ registerKnownNode($relativePath) failed: ${e.message}", e)
    }
    knownNodePaths?.add(relativePath)
    val parentPath = relativePath.substringBeforeLast("/", "")
    nodeChildrenCache.remove(parentPath)
    // courierChildNodes is a separately-cached snapshot of the root listing (used directly
    // by renderNodePicker()'s depth-0 dropdown) — refresh it too so a newly created
    // top-level node (or any node, cheaply, since this just re-reads the now-invalidated
    // or already-correct cache) is visible immediately if the user unlocks and re-opens
    // the picker in the same session.
    courierChildNodes = fetchChildKeysAt("")
}

/** Kicks off the hierarchical node picker by loading courier/'s top-level children. */
internal fun ConfigSheetFragment.fetchCourierChildNodes() {
    viewLifecycleOwner.lifecycleScope.launch {
        courierChildNodes = fetchChildKeysAt("")
        if (isAdded) initNodePicker()
    }
}

/** Sets up the picker: restores existing targetNode path if any, else starts fresh. */
internal fun ConfigSheetFragment.initNodePicker() {
    val existingSuffix = etTargetNode?.text?.toString()?.trim()?.trim('/') ?: ""
    nodePickerPath = if (existingSuffix.isNotBlank()) {
        existingSuffix.split("/").filter { it.isNotBlank() }.toMutableList()
    } else {
        mutableListOf()
    }
    // If editing an already-saved target node, keep its whole path visible (no need to
    // re-click "+ Next level?" for levels that were already picked before), and treat it
    // as already confirmed since its fields were presumably mapped previously.
    nodePickerRevealedDepth = (nodePickerPath.size - 1).coerceAtLeast(0)
    nodeMappingConfirmed = nodePickerPath.isNotEmpty()
    btnResetNodePicker?.setOnClickListener {
        nodePickerPath.clear()
        nodePickerRevealedDepth = 0
        nodeMappingConfirmed = false
        layoutCreateNewNode?.visibility = View.GONE
        renderNodePicker()
    }
    renderNodePicker()
}

internal fun ConfigSheetFragment.updateBreadcrumb() {
    val ctx = context ?: return
    if (nodePickerPath.isEmpty()) {
        tvNodeBreadcrumb?.text = "courier/ —"
        return
    }
    tvNodeBreadcrumb?.text = "courier/" + nodePickerPath.joinToString("/")
}

/** Commits the currently built path as the target node and triggers field auto-detect. */
internal fun ConfigSheetFragment.commitNodePath() {
    val suffix = nodePickerPath.joinToString("/")
    etTargetNode?.setText(suffix)
    val fullNode = "courier/$suffix"
    targetNode = fullNode
    updateBreadcrumb()
    fetchNodeKeys(fullNode)
}

/**
 * Renders one dropdown per revealed depth level of nodePickerPath. A new depth's dropdown
 * only appears after the user taps "+ Next level?" below the deepest one — it never shows
 * automatically just because a selection (or confirm) happened. Once the deepest selection
 * has children, a single tree-preview + confirm box is shown at the bottom (not repeated
 * per depth) so the user can lock in that node as the mapping target using its first child
 * as an example record, or keep drilling instead.
 */
internal fun ConfigSheetFragment.renderNodePicker() {
    val ctx = context ?: return
    val container = containerNodeDropdowns ?: return
    container.removeAllViews()
    layoutCreateNewNode?.visibility = View.GONE
    updateBreadcrumb()

    if (nodeMappingConfirmed) {
        renderLockedNodeSummary(container, ctx)
        return
    }

    viewLifecycleOwner.lifecycleScope.launch {
        var depth = 0
        var currentOptions = courierChildNodes

        while (depth <= nodePickerRevealedDepth) {
            val selectedAtDepth = nodePickerPath.getOrNull(depth)
            addNodeDropdownRow(container, ctx, depth, currentOptions, selectedAtDepth)

            if (selectedAtDepth == null) break // nothing chosen yet at this depth — stop here

            // Fetch this selection's children to decide whether a deeper level exists.
            val childPath = nodePickerPath.subList(0, depth + 1).joinToString("/")
            val children = fetchChildKeysAt(childPath)
            if (!isAdded) return@launch

            currentOptions = children

            if (depth == nodePickerRevealedDepth) {
                // Deepest revealed row — show the action row below it. Selecting a node
                // NEVER auto-locks (even a childless/leaf node): the user decides via the
                // "+ Next level?" / "🔒 Lock this path" buttons here.
                addNodeActionRow(container, ctx, depth, children.size)
                break
            }
            depth++
        }

        // Informational example-data preview for the current deepest selection (read-only —
        // locking is done explicitly via the action row's Lock button, not from here).
        if (nodePickerPath.isNotEmpty()) {
            addTreePreviewSection(container, ctx, nodePickerPath.size - 1, nodePickerPath.joinToString("/"))
        }
    }
}

/** Shown once the node is confirmed — replaces the dropdowns with a read-only summary
 *  and an explicit Unlock button, so the user can't accidentally change the node while
 *  still seeing an editable-looking dropdown. */
internal fun ConfigSheetFragment.renderLockedNodeSummary(container: android.widget.LinearLayout, ctx: android.content.Context) {
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        background = resources.getDrawable(R.drawable.bg_input_rounded, null)
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#F0FDF4")
        )
    }
    box.addView(TextView(ctx).apply {
        text = "🔒 courier/" + nodePickerPath.joinToString("/")
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#16A34A"))
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    box.addView(TextView(ctx).apply {
        text = "🔓 Unlock"
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#EF4444"))
        isClickable = true
        isFocusable = true
        setPadding(10.dp(), 4.dp(), 4.dp(), 4.dp())
        setOnClickListener { unlockNodePicker() }
    })
    container.addView(box)
}

/** Unlocks the node picker so the user can pick a different node. Warns first if there are
 *  already-mapped fields, since changing the node may make those column mappings irrelevant
 *  (they aren't auto-cleared — just flagged as a risk before the user proceeds). */
internal fun ConfigSheetFragment.unlockNodePicker() {
    val hasExistingMapping = pendingMapping.isNotEmpty() || pendingObjectMapping.isNotEmpty() || pendingPkParts.isNotEmpty()
    if (!hasExistingMapping) {
        nodeMappingConfirmed = false
        renderNodePicker()
        renderMappingStep()
        return
    }
    android.app.AlertDialog.Builder(requireContext())
        .setTitle("Node পরিবর্তন করবেন?")
        .setMessage("Primary key ও field mapping ইতিমধ্যে সেট করা আছে। Node পরিবর্তন করলে এই mapping গুলো নতুন node এর জন্য সঠিক নাও হতে পারে। আপনাকে সেগুলো আবার review করতে হবে।\n\nContinue করবেন?")
        .setPositiveButton("হ্যাঁ, Node পরিবর্তন করবো") { _, _ ->
            nodeMappingConfirmed = false
            renderNodePicker()
            renderMappingStep()
        }
        .setNegativeButton("না, থাকুক", null)
        .show()
}

/** Builds and adds a single dropdown row for one depth level. */
internal fun ConfigSheetFragment.addNodeDropdownRow(
    container: android.widget.LinearLayout,
    ctx: android.content.Context,
    depth: Int,
    options: List<String>,
    selectedKey: String?
) {
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val row = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8.dp() }
    }

    if (depth > 0) {
        val connector = TextView(ctx).apply {
            text = "└─"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#E8380D"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (depth * 12).dp(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(connector)
    }

    val spinner = Spinner(ctx).apply {
        background = resources.getDrawable(R.drawable.bg_input_rounded, null)
        layoutParams = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { height = 44.dp() }
    }
    val labels = listOf("— select করুন —") + options + listOf("+ Create New")
    spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
    val selIdx = selectedKey?.let { options.indexOf(it) + 1 } ?: 0
    spinner.setSelection(selIdx.coerceAtLeast(0))

    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos == 0) return // placeholder
            if (pos == labels.size - 1) {
                // "+ Create New" chosen at this depth
                showCreateNewNodeInput(depth)
                return
            }
            val chosen = options[pos - 1]
            if (nodePickerPath.getOrNull(depth) == chosen) return // no-op re-select
            // Truncate path to this depth, then set the new choice
            nodePickerPath = nodePickerPath.subList(0, depth).toMutableList()
            nodePickerPath.add(chosen)
            nodePickerRevealedDepth = depth
            nodeMappingConfirmed = false
            renderNodePicker()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    row.addView(spinner)

    // ✕ button for ALL depths (depth 0 resets entire path)
    val btnCancel = TextView(ctx).apply {
        text = "✕"
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.parseColor("#EF4444"))
        setPadding(10.dp(), 6.dp(), 4.dp(), 6.dp())
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { cancelNodeAtDepth(depth) }
    }
    row.addView(btnCancel)

    container.addView(row)
}

/** ✕ pressed on the dropdown at [depth] — removes this dropdown and everything deeper
 *  than it entirely, reverting to the parent depth's "+ Next level?" button (as if that
 *  level had never been drilled into). Depth 0 clears the whole path back to the start. */
internal fun ConfigSheetFragment.cancelNodeAtDepth(depth: Int) {
    if (depth == 0) {
        nodePickerPath.clear()
        nodePickerRevealedDepth = 0
    } else {
        nodePickerPath = nodePickerPath.subList(0, depth).toMutableList()
        nodePickerRevealedDepth = depth - 1
    }
    nodeMappingConfirmed = false
    renderNodePicker()
}

/** Action row rendered below the deepest selected dropdown. Offers two explicit choices:
 *  "+ Next level?" (reveal a child dropdown to drill deeper or Create New a child) and
 *  "🔒 Lock this path" (commit the current path as the mapping target). Selecting a node
 *  alone never locks — locking is always the user's explicit action here. */
internal fun ConfigSheetFragment.addNodeActionRow(
    container: android.widget.LinearLayout,
    ctx: android.content.Context,
    depth: Int,
    childCount: Int
) {
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val row = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(4.dp(), 2.dp(), 4.dp(), 10.dp())
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    if (childCount > MAX_DRILLABLE_CHILDREN) {
        // Likely a dynamic-key collection (e.g. hundreds/thousands of run IDs) — dumping
        // all of them into a dropdown isn't useful, so hide "+ Next level?" and let the
        // user lock this path using the example preview below.
        row.addView(TextView(ctx).apply {
            text = "⚠ $childCount টা dynamic ID — dropdown এ নয়, নিচের preview অনুযায়ী Lock করুন"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    } else {
        // Always shown — even for a childless node — so the user can drill in and
        // Create New a child under it (courier/run_routes → courier/run_routes/delivery_run).
        row.addView(TextView(ctx).apply {
            text = "+ Next level?"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#2563EB"))
            setPadding(4.dp(), 6.dp(), 4.dp(), 6.dp())
            isClickable = true
            isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                nodePickerRevealedDepth = depth + 1
                renderNodePicker()
            }
        })
    }

    row.addView(android.widget.Button(ctx).apply {
        text = "🔒 Lock this path"
        textSize = 11f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#16A34A")
        )
        setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { lockNodePath(depth) }
    })

    container.addView(row)
}

/** Locks the path up to [depth] as the target node and runs the normal commit + auto-map
 *  flow. This is the ONLY place a selection becomes the committed mapping target. */
internal fun ConfigSheetFragment.lockNodePath(depth: Int) {
    nodePickerPath = nodePickerPath.subList(0, depth + 1).toMutableList()
    nodeMappingConfirmed = true
    commitNodePath()
    renderNodePicker()
    renderMappingStep()
}

/**
 * Fetches the first example child under "courier/$pathSoFar" and renders it as a nested
 * tree, with a confirm button that locks in [pathSoFar] as the target node (using this
 * example record's fields for auto-mapping) — without forcing the user to keep drilling
 * into a dropdown of raw dynamic keys (e.g. individual run IDs).
 */
internal fun ConfigSheetFragment.addTreePreviewSection(
    container: android.widget.LinearLayout,
    ctx: android.content.Context,
    depth: Int,
    pathSoFar: String
) {
    val dp = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        background = resources.getDrawable(R.drawable.bg_input_rounded, null)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10.dp() }
    }
    box.addView(TextView(ctx).apply {
        text = "⏳ Example data লোড হচ্ছে…"
        textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#6B7280"))
    })
    container.addView(box)

    viewLifecycleOwner.lifecycleScope.launch {
        val snap = try {
            withContext(Dispatchers.IO) {
                // Fetch 2 so a leading hidden meta marker can be skipped while still leaving
                // a real example record (only one meta marker can ever precede real records).
                db.reference.child("courier/$pathSoFar").limitToFirst(2).get().await()
            }
        } catch (e: Exception) {
            Log.e("ConfigSheet", "❌ tree preview fetch failed for $pathSoFar: ${e.message}", e)
            null
        }
        if (!isAdded) return@launch
        box.removeAllViews()

        val firstChild = snap?.children?.firstOrNull { it.key != NODE_META_KEY }
        if (snap == null || !snap.exists() || firstChild == null) {
            box.addView(TextView(ctx).apply {
                text = "⚠ এখানে এখনো কোনো example data নেই (খালি node) — child তৈরি করতে \"+ Next level?\" ব্যবহার করুন"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            })
            return@launch
        }
        val treeText = buildFirebaseTreeString(firstChild, 0).ifBlank { "(no fields)" }

        box.addView(TextView(ctx).apply {
            text = "📄 Example (${firstChild.key}):"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        box.addView(TextView(ctx).apply {
            text = treeText
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.parseColor("#111827"))
            setPadding(0, 6.dp(), 0, 6.dp())
        })
        box.addView(TextView(ctx).apply {
            text = "☝️ এই node এর ডেটা এমন দেখতে। এটাকে target হিসেবে নিতে উপরের \"🔒 Lock this path\" চাপুন।"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            setPadding(0, 6.dp(), 0, 0)
        })
    }
}

/** Recursively renders a DataSnapshot as an indented tree string (nested objects included).
 *  Only ONE example nested child is recursed into at any level (e.g. one run under
 *  delivery_run, not three) — showing multiple siblings just repeats the same shape and
 *  makes the preview needlessly long. A node's own flat (scalar) fields are shown in full
 *  UNLESS there are many of them (> 15), which means this node itself is a bulk key-value
 *  collection (e.g. consignments/{id}: "status") rather than one record's own named fields —
 *  those get capped to 3, since every entry has the identical shape anyway. The threshold is
 *  set well above any single record's realistic field count (a consignment or run typically
 *  has well under 15 fields, and that count can vary a bit between records) so an individual
 *  record's fields are never truncated — only a true repeated-entries collection is. */
internal fun ConfigSheetFragment.buildFirebaseTreeString(
    snap: com.google.firebase.database.DataSnapshot,
    indent: Int
): String {
    val pad = "  ".repeat(indent)
    val sb = StringBuilder()
    val allChildren = snap.children.toList().filter { it.key != NODE_META_KEY }
    val flatChildren   = allChildren.filter { !it.hasChildren() }
    val nestedChildren = allChildren.filter { it.hasChildren() }

    val flatToShow = if (flatChildren.size > 15) flatChildren.take(3) else flatChildren
    flatToShow.forEach { child ->
        val key = child.key ?: return@forEach
        val v = child.value?.toString()?.take(40) ?: ""
        sb.append("$pad├─ $key: $v\n")
    }
    nestedChildren.take(1).forEach { child ->
        val key = child.key ?: return@forEach
        sb.append("$pad├─ $key:\n")
        sb.append(buildFirebaseTreeString(child, indent + 1))
    }
    return sb.toString()
}

/** Shows the inline "create new node" input, wired to insert at the given depth. On confirm
 *  the node is physically created under courier/ (with a hidden meta marker so an empty node
 *  persists and re-appears on the next fetch) and revealed as the deepest selection — the
 *  user is NOT auto-locked, so they can either drill deeper (Create New a child) or Lock. */
internal fun ConfigSheetFragment.showCreateNewNodeInput(depth: Int) {
    layoutCreateNewNode?.visibility = View.VISIBLE
    etNewNodeName?.setText("")
    etNewNodeName?.requestFocus()

    btnConfirmNewNode?.setOnClickListener {
        val name = etNewNodeName?.text?.toString()?.trim()?.trim('/') ?: ""
        if (name.isBlank()) {
            toast("⚠ Node name দিন")
            return@setOnClickListener
        }
        // Firebase keys can't contain  . # $ [ ] /  — reject so this stays exactly one level.
        if (name.contains(Regex("[./#$\\[\\]]"))) {
            toast("⚠ Node name এ  . # \$ [ ] /  ব্যবহার করা যাবে না")
            return@setOnClickListener
        }
        nodePickerPath = nodePickerPath.subList(0, depth).toMutableList()
        nodePickerPath.add(name)
        layoutCreateNewNode?.visibility = View.GONE
        val newPath = nodePickerPath.joinToString("/")
        val parentPath = nodePickerPath.subList(0, depth).joinToString("/")

        viewLifecycleOwner.lifecycleScope.launch {
            // Physically create courier/{newPath} (live) with a hidden meta marker so the
            // otherwise-empty node persists and shows up on the next shallow fetch. Keep the
            // known_nodes registry entry too as a fallback for the dropdown merge.
            createNodePhysically(newPath)
            registerKnownNode(newPath)
            if (!isAdded) return@launch
            // Invalidate the parent's cached child list so the new node appears immediately.
            nodeChildrenCache.remove(parentPath)
            if (parentPath.isBlank()) courierChildNodes = fetchChildKeysAt("")
            // Do NOT auto-lock. Reveal the new node as the deepest selection so the user can
            // drill deeper (Create New a child) or explicitly Lock it as the target.
            nodePickerRevealedDepth = depth
            nodeMappingConfirmed = false
            renderNodePicker()
            toast("✅ courier/$newPath তৈরি হয়েছে")
        }
    }
    btnCancelNewNode?.setOnClickListener {
        layoutCreateNewNode?.visibility = View.GONE
    }
}

/** Physically creates courier/[relativePath] in Firebase by writing a hidden meta marker
 *  child, so an otherwise-empty node persists (Firebase drops childless nodes) and appears
 *  in the next shallow child listing. The marker key ([NODE_META_KEY]) is filtered out of
 *  every wizard listing/preview, so it's never shown as a child, example record, or field. */
internal suspend fun ConfigSheetFragment.createNodePhysically(relativePath: String) {
    if (relativePath.isBlank()) return
    try {
        withContext(Dispatchers.IO) {
            db.reference.child("courier/$relativePath/$NODE_META_KEY")
                .setValue(mapOf("created_at" to System.currentTimeMillis()))
                .await()
        }
    } catch (e: Exception) {
        Log.e("ConfigSheet", "❌ createNodePhysically($relativePath) failed: ${e.message}", e)
    }
}

/** Re-renders the picker after creating a brand-new node, without a failed child-fetch. */
internal fun ConfigSheetFragment.renderNodePickerKeepingNewNode(depth: Int, newName: String) {
    val ctx = context ?: return
    val container = containerNodeDropdowns ?: return
    container.removeAllViews()
    updateBreadcrumb()

    // Render existing depths normally, then the final row shows the new node as selected
    // with no further drill-down (since it doesn't exist in Firebase yet).
    var options = courierChildNodes
    for (d in 0 until depth) {
        addNodeDropdownRow(container, ctx, d, options, nodePickerPath.getOrNull(d))
        val childPath = nodePickerPath.subList(0, d + 1).joinToString("/")
        options = nodeChildrenCache[childPath] ?: emptyList()
    }
    addNodeDropdownRow(container, ctx, depth, options, newName)
}

internal fun ConfigSheetFragment.fetchNodeKeys(node: String) {
    pbFetchFields?.visibility = View.VISIBLE
    tvFetchStatus?.text = "Fetching..."
    btnFetchFields?.isEnabled = false

    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val snap = withContext(Dispatchers.IO) {
                // Fetch 2 so a leading hidden meta marker can be skipped and still leave a
                // real example record (only one meta marker can ever precede real records).
                db.reference.child(node).limitToFirst(2).get().await()
            }
            if (!isAdded) return@launch
            pbFetchFields?.visibility = View.GONE
            btnFetchFields?.isEnabled = true

            // Pick the first real child, skipping the hidden meta marker of a freshly
            // "+ Create New"-ed node. If nothing but the marker exists, treat as empty.
            val exampleChild = if (snap.hasChildren())
                snap.children.firstOrNull { it.key != NODE_META_KEY }
            else snap

            if (!snap.exists() || exampleChild == null) {
                tvFetchStatus?.text = "⚠ Data নেই — manually field add করুন"
                tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                nodePreviewData = emptyMap()
                // When reconnecting an existing connection, keep the saved mapping visible even
                // if the node currently has no data — otherwise the preview would blank out.
                if (!isEditingExistingConn()) {
                    fetchedNodeKeys.clear()
                    customMappingFields.clear()
                    objectTypeFields.clear()
                    pendingObjectMapping.clear()
                }
                renderMappingStep()
                return@launch
            }

            val firstChild = exampleChild
            val keys = firstChild.children.mapNotNull { it.key }.filter { it != NODE_META_KEY }.toList()

            // Store preview values
            nodePreviewData = firstChild.children.mapNotNull { child ->
                val k = child.key ?: return@mapNotNull null
                if (k == NODE_META_KEY) return@mapNotNull null
                val v = child.value?.toString()?.take(40) ?: ""
                k to v
            }.toMap()

            if (keys.isEmpty()) {
                tvFetchStatus?.text = "⚠ Keys পাওয়া যায়নি — manually add করুন"
                tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                return@launch
            }

            // Snapshot saved mappings so a reconnect "Fetch Fields" never destroys the user's
            // saved object config — fresh auto-detection runs first, then saved values are
            // layered back on top (saved always wins). Flat mapping survives automatically
            // because it's never cleared here and autoDetectMapping() is non-destructive.
            val editingExisting = isEditingExistingConn()
            val savedObjMapping    = if (editingExisting) HashMap(pendingObjectMapping) else null
            val savedObjTypeFields = if (editingExisting) HashSet(objectTypeFields) else null

            fetchedNodeKeys.clear()
            fetchedNodeKeys.addAll(keys)
            customMappingFields.clear()
            pendingObjectMapping.clear()

            // Auto-detect object-type fields (children that are themselves objects/maps)
            objectTypeFields.clear()
            firstChild.children.forEach { child ->
                val k = child.key ?: return@forEach
                if (child.hasChildren()) {
                    // Check if it's an object (map) not just a nested single value
                    val firstGrandChild = child.children.firstOrNull()
                    if (firstGrandChild != null) {
                        objectTypeFields.add(k)
                        // Auto fuzzy match key + value columns
                        val keyHeader = sheetHeaders.entries.firstOrNull { (_, h) ->
                            val hl = h.lowercase()
                            listOf("id", "con", "consignment", "key", "code").any { hl.contains(it) }
                        }
                        val valHeader = sheetHeaders.entries.firstOrNull { (_, h) ->
                            val hl = h.lowercase()
                            listOf("status", "state", "value").any { hl.contains(it) }
                        }
                        if (keyHeader != null && valHeader != null) {
                            pendingObjectMapping[k] = ObjectColMapping(
                                keyCol      = keyHeader.key,
                                keyHeader   = keyHeader.value,
                                valueCol    = valHeader.key,
                                valueHeader = valHeader.value,
                            )
                        }
                    }
                }
            }

            // Restore saved object mapping on top of fresh auto-detection (reconnect only).
            savedObjTypeFields?.let { objectTypeFields.addAll(it) }
            savedObjMapping?.let { pendingObjectMapping.putAll(it) }

            autoDetectMapping()
            nodePreviewExpanded = true
            renderMappingStep()

            tvFetchStatus?.text = "✅ ${keys.size} fields found"
            tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#16A34A"))

        } catch (e: Exception) {
            if (!isAdded) return@launch
            pbFetchFields?.visibility = View.GONE
            btnFetchFields?.isEnabled = true
            tvFetchStatus?.text = "⚠ Fetch failed: ${e.message?.take(40)}"
            tvFetchStatus?.setTextColor(android.graphics.Color.parseColor("#EF4444"))
        }
    }
}
