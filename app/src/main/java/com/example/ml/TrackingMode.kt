package com.example.ml

enum class TrackingMode {
    ALL_OBJECTS,
    PROMINENT_ONLY,
    LOCKED_TARGET
}

sealed interface DetectorStatus {
    object Initializing : DetectorStatus
    object Ready : DetectorStatus
    data class Error(val message: String) : DetectorStatus
}
