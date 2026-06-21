package com.cloudx.databridge

import com.google.firebase.database.DataSnapshot

/** Data classes + helpers for admin-created salary models. */
data class SalarySlab(
    val min: Int = 0,
    val max: Int = 0, // 0 or negative => no upper bound
    val rate: Double = 0.0
)

data class SalaryModelConfig(
    val id: String = "",
    val name: String = "",
    val type: String = TYPE_FLAT,
    val fixedSalary: Double = 0.0,
    val documentDeliveryRate: Double = 0.0,
    val documentPickupRate: Double = 0.0,
    val slabs: List<SalarySlab> = emptyList(),            // delivery slabs (legacy key: slabs / new key: delivery_slabs)
    val pickupSlabs: List<SalarySlab> = emptyList()
) {
    companion object {
        const val TYPE_FLAT = "flat_rate"
        const val TYPE_TIERED = "tiered_cumulative"
        const val TYPE_PERCENT = "percent_rate"
    }
}

data class SalaryModelOption(val id: String, val name: String)

data class PickupCommissionSlab(
    val minPercent: Int = 0,
    val maxPercent: Int = 0,
    val perDeliveryCommission: Double = 0.0
)

data class SalaryCommissionLine(
    val label: String,
    val quantity: Int,
    val amount: Double
)

data class SalaryCommissionResult(
    val parcelLines: List<SalaryCommissionLine>,
    val documentAmount: Double,
    val total: Double
)

fun DataSnapshot.toSalaryModelConfig(modelKey: String? = null): SalaryModelConfig {
    val id = modelKey ?: key.orEmpty()
    val name = child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: id
    val type = child("type").getValue(String::class.java)
        ?.let {
            when (it) {
                "flat" -> SalaryModelConfig.TYPE_FLAT
                "tiered" -> SalaryModelConfig.TYPE_TIERED
                "percent" -> SalaryModelConfig.TYPE_PERCENT
                else -> it
            }
        }
        ?.takeIf {
            it == SalaryModelConfig.TYPE_FLAT || it == SalaryModelConfig.TYPE_TIERED || it == SalaryModelConfig.TYPE_PERCENT
        }
        ?: SalaryModelConfig.TYPE_FLAT
    val fixedSalary = child("fixed_salary").numberValue() ?: 0.0
    val documentDeliveryRate = child("document_delivery_rate").numberValue()
        ?: child("doc_rate").numberValue()
        ?: child("document_rate").numberValue()
        ?: 0.0
    val documentPickupRate = child("document_pickup_rate").numberValue() ?: documentDeliveryRate
    val deliverySlabs = (child("delivery_slabs").takeIf { it.exists() } ?: child("slabs")).children.mapNotNull { c ->
        val min = c.child("min").intValue() ?: return@mapNotNull null
        val max = c.child("max").intValue() ?: 0
        val rate = c.child("rate").numberValue() ?: return@mapNotNull null
        SalarySlab(min, max, rate)
    }.sortedBy { it.min }

    val pickupSlabs = child("pickup_slabs").children.mapNotNull { c ->
        val min = c.child("min").intValue() ?: return@mapNotNull null
        val max = c.child("max").intValue() ?: 0
        val rate = c.child("rate").numberValue() ?: return@mapNotNull null
        SalarySlab(min, max, rate)
    }.sortedBy { it.min }

    return SalaryModelConfig(id, name, type, fixedSalary, documentDeliveryRate, documentPickupRate, deliverySlabs, pickupSlabs)
}

fun DataSnapshot.toSalaryModelOptions(): List<SalaryModelOption> {
    return children.mapNotNull { child ->
        val id = child.key ?: return@mapNotNull null
        val name = child.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: id
        SalaryModelOption(id, name)
    }.sortedBy { it.name.lowercase() }
}

fun SalaryModelConfig.toFirebaseMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "type" to type,
    "document_delivery_rate" to documentDeliveryRate,
    "document_pickup_rate" to documentPickupRate,
    "delivery_slabs" to slabs.map { slab ->
        mapOf(
            "min" to slab.min,
            "max" to if (slab.max <= 0) null else slab.max,
            "rate" to slab.rate
        )
    },
    "pickup_slabs" to pickupSlabs.map { slab ->
        mapOf(
            "min" to slab.min,
            "max" to if (slab.max <= 0) null else slab.max,
            "rate" to slab.rate
        )
    }
)

fun DataSnapshot.toPickupCommissionSlabs(): List<PickupCommissionSlab> {
    return children.mapNotNull { c ->
        val min = c.child("min_percent").intValue() ?: return@mapNotNull null
        val max = c.child("max_percent").intValue() ?: return@mapNotNull null
        val commission = c.child("per_delivery_commission").numberValue() ?: return@mapNotNull null
        PickupCommissionSlab(min, max, commission)
    }.sortedBy { it.minPercent }
}

fun pickupSlabsToFirebaseList(slabs: List<PickupCommissionSlab>): List<Map<String, Any>> {
    return slabs.map {
        mapOf(
            "min_percent" to it.minPercent,
            "max_percent" to it.maxPercent,
            "per_delivery_commission" to it.perDeliveryCommission
        )
    }
}

fun SalaryModelConfig.calculateCommission(parcelQty: Int, documentQty: Int): SalaryCommissionResult {
    val safeParcelQty = parcelQty.coerceAtLeast(0)
    val safeDocumentQty = documentQty.coerceAtLeast(0)
    val parcelLines = if (safeParcelQty == 0 || slabs.isEmpty()) {
        emptyList()
    } else if (type == SalaryModelConfig.TYPE_TIERED) {
        val lines = mutableListOf<SalaryCommissionLine>()
        var previousEnd = 0
        slabs.sortedBy { it.min }.forEach { slab ->
            val lower = slab.min.coerceAtLeast(previousEnd + 1)
            val upper = if (slab.max <= 0) safeParcelQty else slab.max.coerceAtMost(safeParcelQty)
            if (safeParcelQty >= lower && upper >= lower) {
                val used = upper - lower + 1
                lines.add(
                    SalaryCommissionLine(
                        label = "${lower}-${if (slab.max <= 0) "+" else slab.max} x ${formatMoneyPlain(slab.rate)}",
                        quantity = used,
                        amount = used * slab.rate
                    )
                )
            }
            previousEnd = if (slab.max <= 0) safeParcelQty else slab.max
        }
        lines
    } else {
        val slab = slabs.firstOrNull {
            val max = if (it.max <= 0) Int.MAX_VALUE else it.max
            safeParcelQty in it.min..max
        } ?: slabs.last()
        listOf(
            SalaryCommissionLine(
                label = "$safeParcelQty deliveries x ${formatMoneyPlain(slab.rate)}",
                quantity = safeParcelQty,
                amount = safeParcelQty * slab.rate
            )
        )
    }
    val docAmount = safeDocumentQty * documentDeliveryRate
    return SalaryCommissionResult(
        parcelLines = parcelLines,
        documentAmount = docAmount,
        total = parcelLines.sumOf { it.amount } + docAmount
    )
}

fun formatMoneyPlain(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}

private fun DataSnapshot.numberValue(): Double? {
    return getValue(Double::class.java)
        ?: getValue(Long::class.java)?.toDouble()
        ?: getValue(Int::class.java)?.toDouble()
}

private fun DataSnapshot.intValue(): Int? {
    return getValue(Int::class.java)
        ?: getValue(Long::class.java)?.toInt()
        ?: getValue(Double::class.java)?.toInt()
}
