package com.cloudx.databridge

data class MemoryEntry(
    val id: String = "",
    val parcelDelivery: Int = 0,
    val documentDelivery: Int = 0,
    val parcelPickup: Int = 0,
    val documentPickup: Int = 0,
    val parcelAssigned: Int = 0,
    val documentAssigned: Int = 0,
    val parcelPickupAssigned: Int = 0,
    val documentPickupAssigned: Int = 0,
    val parcelCommission: Double = 0.0,
    val documentCommission: Double = 0.0,
    val parcelPickupCommission: Double = 0.0,
    val documentPickupCommission: Double = 0.0,
    val createdAt: Long = 0L,
    val model: String = "",
    val parcelSuccessRate: Double = 0.0,
    val documentSuccessRate: Double = 0.0,
    val parcelPickupSuccessRate: Double = 0.0,
    val documentPickupSuccessRate: Double = 0.0
)
