package com.shilapi.xcertplay

/** One run's lines, as the report reads them off disk. */
internal data class DiagnosticSection(
    val name: String,
    val lines: List<String>,
    val newest: Boolean,
)
