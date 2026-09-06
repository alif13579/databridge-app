package com.cloudx.databridge

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Routing Approval — DEMO STAGE (demo data only, nothing persisted).
 *
 * Agent A (branch "abc" / Madanpur) opens this fragment and sees, for THEIR
 * branch only:
 * - Incoming tab: parcels other branches sent TODAY to this branch
 *   (toBranchId == myBranchId). Every card has 3 decision buttons:
 *   Approved / Wrong hub / Improper address.
 * - Outgoing tab: parcels this branch sent out today
 *   (fromBranchId == myBranchId). Info only — the RECEIVING branch approves
 *   those on their own Incoming tab, so no buttons here.
 *
 * Identity comes from RBAC ([RbacManager.current.branchIds]/[branchName]);
 * when RBAC is empty (signed out / preview) it falls back to the demo
 * identity abc/Madanpur so the screen is still reviewable.
 *
 * Real wiring later: incoming = today's runs/routes where destination ==
 * myBranchId; outgoing = today's runs/routes where origin == myBranchId;
 * decisions write to whichever node the routing schema settles on (same
 * open question as VirtualRoutingFragment's routeParcel()).
 */
class RoutingApprovalFragment : Fragment() {

    private val myBranchId: String
        get() = RbacManager.current.branchIds.firstOrNull()?.trim()
            .takeIf { !it.isNullOrBlank() } ?: "abc"

    private val myBranchName: String
        get() = RbacManager.current.branchName.trim()
            .takeIf { it.isNotBlank() } ?: "Madanpur"

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
        var decision: String = "", // "", "approved", "wrong_hub", "improper_address"
    )

    private val incoming = mutableListOf<RouteParcel>()
    private val outgoing = mutableListOf<RouteParcel>()
    private var tab = "incoming"

    private var tabIncomingBtn: TextView? = null
    private var tabOutgoingBtn: TextView? = null
    private var cardsBox: LinearLayout? = null
    private var seededFor: String = ""

    private fun dp(v: Int): Int =
        (v * (resources.displayMetrics.density)).toInt()

    // ── Demo data ────────────────────────────────────────────────────────────
    private fun seedDemo() {
        if (seededFor == myBranchId && (incoming.isNotEmpty() || outgoing.isNotEmpty())) return
        seededFor = myBranchId
        incoming.clear()
        outgoing.clear()
        val me = myBranchId
        val myName = myBranchName
        // Other branches sent THESE to me today → I approve them here.
        incoming.addAll(listOf(
            RouteParcel("CXB-88231", "gls", "Gulshan", me, myName,
                "Rahim Uddin", "01811-223344", "House 12, Road 5, Madanpur Bazar", 1250),
            RouteParcel("CXB-88247", "mrp", "Mirpur", me, myName,
                "Fatema Begum", "01922-334455", "Holding 88, Station Road", 780),
            RouteParcel("CXB-88302", "utr", "Uttara", me, myName,
                "Kamal Hossain", "01733-445566", "Plot 7, Block C", 2100),
            RouteParcel("CXB-88319", "jtb", "Jatrabari", me, myName,
                "Nasrin Akter", "01644-556677", "Shop 21, Madanpur Chowrasta", 540),
        ))
        // I sent THESE out today → the receiving branch approves on THEIR incoming.
        outgoing.addAll(listOf(
            RouteParcel("CXB-88105", me, myName, "gls", "Gulshan",
                "Tanvir Ahmed", "01855-667788", "House 9, Road 11, Banani", 3400),
            RouteParcel("CXB-88112", me, myName, "mrp", "Mirpur",
                "Shirin Sultana", "01966-778899", "Flat 4B, Darussalam Road", 960),
            RouteParcel("CXB-88130", me, myName, "utr", "Uttara",
                "Arif Chowdhury", "01777-889900", "Sector 7, Lake Drive Road", 1750),
        ))
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        seedDemo()
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "Routing Approval"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        })
        root.addView(TextView(ctx).apply {
            text = "Branch: $myBranchName ($myBranchId)"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(2), 0, dp(2))
        })
        root.addView(TextView(ctx).apply {
            text = "DEMO — demo data, kichu save hoyna"
            textSize = 11f
            setTextColor(Color.parseColor("#B45309"))
            setPadding(0, 0, 0, dp(12))
        })

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
        return scroll
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
        metaLine("🔀 ${parcel.fromBranchName} (${parcel.fromBranchId}) → ${parcel.toBranchName} (${parcel.toBranchId})")
        metaLine("👤 ${parcel.customer} • ${parcel.phone}")
        metaLine("📍 ${parcel.address}")
        metaLine("💰 COD ৳${parcel.cod}")

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
                text = "Receiving branch (${parcel.toBranchName}) approve korbe"
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
