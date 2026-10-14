package com.example.data.model

data class FrameTelemetry(
    val timeMs: Long,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val velocity: String,
    val trackId: Int
)
