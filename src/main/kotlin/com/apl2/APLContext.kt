package com.apl2

data class APLContext(
    val indexOrigin: Int = 0,
    val printWidth: Int = 0,
    val printPrecision: Int = -1,
    val comparisonTolerance: Double = 1e-15,
) {
    init {
        require(printWidth >= 0) { "Print width must be non-negative" }
        require(printPrecision >= -1) { "Print precision must be -1 or greater" }
        require(comparisonTolerance >= 0.0) { "Comparison tolerance must be non-negative" }
    }

    companion object {
        val DEFAULT = APLContext()
    }
}
