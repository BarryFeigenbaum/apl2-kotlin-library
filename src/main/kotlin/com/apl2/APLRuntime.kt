package com.apl2

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

data class APLComplex(val real: Double, val imaginary: Double)

class APLArray<T>(
    private val elements: List<T>,
    private val shape: IntArray = intArrayOf(elements.size),
) {
    init {
        require(shape.isNotEmpty()) { "Shape must not be empty" }
        require(shape.all { it >= 0 }) { "Shape dimensions must be non-negative" }
        require(shape.fold(1L) { product, dim -> product * dim } == elements.size.toLong()) {
            "Shape does not match element count"
        }
    }

    fun size(): Int = elements.size

    fun shape(): IntArray = shape.clone()

    fun rawElement(index: Int): T = elements[index]

    fun getElement(index: Int): T = elements[APLRuntime.toZeroBasedIndex(index, elements.size)]

    fun getElement(vararg indices: Int): T {
        require(indices.size == shape.size) { "Expected ${shape.size} indices but got ${indices.size}" }
        val zeroBased = APLRuntime.toZeroBasedIndices(indices, shape)
        var linearIndex = 0
        for (i in zeroBased.indices) {
            linearIndex = linearIndex * shape[i] + zeroBased[i]
        }
        return elements[linearIndex]
    }
}

object APLRuntime {
    private val contextStack = ThreadLocal.withInitial { ArrayDeque<APLContext>() }

    fun createContext(): APLContext = APLContext()

    fun createContext(context: APLContext): APLContext = context.copy()

    fun destroyContext() {
        contextStack.remove()
    }

    fun pushContext(context: APLContext): APLContext {
        contextStack.get().addFirst(context)
        return context
    }

    fun popContext(): APLContext {
        val stack = contextStack.get()
        if (stack.isEmpty()) {
            throw IllegalStateException("No runtime context is currently active")
        }
        val popped = stack.removeFirst()
        if (stack.isEmpty()) {
            contextStack.remove()
        }
        return popped
    }

    fun currentContext(): APLContext = contextStack.get().firstOrNull() ?: APLContext.DEFAULT

    fun toZeroBasedIndex(index: Int, size: Int): Int {
        val zeroBasedIndex = index - currentContext().indexOrigin
        if (zeroBasedIndex !in 0 until size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }
        return zeroBasedIndex
    }

    fun toZeroBasedIndices(indices: IntArray, shape: IntArray): IntArray {
        require(indices.size == shape.size) { "Index rank ${indices.size} does not match shape rank ${shape.size}" }

        val indexOrigin = currentContext().indexOrigin
        return IntArray(indices.size) { i ->
            val zeroBased = indices[i] - indexOrigin
            if (zeroBased !in 0 until shape[i]) {
                throw IndexOutOfBoundsException("Index ${indices.contentToString()} out of bounds for shape ${shape.contentToString()}")
            }
            zeroBased
        }
    }

    fun toOriginIndex(zeroBasedIndex: Long): Long = zeroBasedIndex + currentContext().indexOrigin

    fun toOriginIndex(zeroBasedIndex: Int): Int = zeroBasedIndex + currentContext().indexOrigin

    fun toOriginIndices(zeroBasedIndices: IntArray): IntArray {
        val indexOrigin = currentContext().indexOrigin
        return IntArray(zeroBasedIndices.size) { i -> zeroBasedIndices[i] + indexOrigin }
    }

    fun areClose(left: Double, right: Double): Boolean =
        when {
            left.isNaN() || right.isNaN() -> left.isNaN() && right.isNaN()
            left.isInfinite() || right.isInfinite() -> left == right
            else -> abs(left - right) <= currentContext().comparisonTolerance
        }

    fun valuesEqual(left: Any?, right: Any?): Boolean {
        if (left === right) return true
        if (left == null || right == null) return false

        return when {
            left is APLArray<*> && right is APLArray<*> -> {
                left.shape().contentEquals(right.shape()) &&
                    left.size() == right.size() &&
                    (0 until left.size()).all { i ->
                    valuesEqual(left.rawElement(i), right.rawElement(i))
                }
            }
            left is APLComplex && right is APLComplex -> {
                areClose(left.real, right.real) && areClose(left.imaginary, right.imaginary)
            }
            isUnsignedNumber(left) && isUnsignedNumber(right) -> {
                unsignedToBigDecimal(left).compareTo(unsignedToBigDecimal(right)) == 0
            }
            left is Number && !isUnsignedNumber(left) && isUnsignedNumber(right) -> {
                if (isSignedIntegralNumber(left)) mixedSignedUnsignedEqual(left, right) else false
            }
            isUnsignedNumber(left) && right is Number && !isUnsignedNumber(right) -> {
                if (isSignedIntegralNumber(right)) mixedSignedUnsignedEqual(right, left) else false
            }
            left is Number && right is Number -> numbersEqual(left, right)
            else -> left == right
        }
    }

    fun format(value: Any?): String {
        if (value is APLArray<*>) {
            val values = (0 until value.size()).joinToString(", ") { i -> format(value.rawElement(i)) }
            return "[$values]"
        }

        return when (value) {
            null -> "null"
            is APLComplex -> formatComplex(value)
            is UByte -> formatUnsigned(value)
            is UShort -> formatUnsigned(value)
            is UInt -> formatUnsigned(value)
            is ULong -> formatUnsigned(value)
            is Number -> formatScalarNumber(value)
            else -> applyWidth(value.toString())
        }
    }

    private fun numbersEqual(left: Number, right: Number): Boolean {
        val leftDouble = left.toDouble()
        val rightDouble = right.toDouble()
        if (!leftDouble.isFinite() || !rightDouble.isFinite()) {
            return areClose(leftDouble, rightDouble)
        }

        val leftIntegral = isSignedIntegralNumber(left)
        val rightIntegral = isSignedIntegralNumber(right)
        return if (leftIntegral && rightIntegral) {
            left.toLong() == right.toLong()
        } else {
            val leftDecimal = toBigDecimal(left)
            val rightDecimal = toBigDecimal(right)
            val tolerance = BigDecimal.valueOf(currentContext().comparisonTolerance)
            leftDecimal.subtract(rightDecimal).abs() <= tolerance
        }
    }

    private fun mixedSignedUnsignedEqual(signed: Number, unsigned: Any): Boolean {
        val signedIntegral = isSignedIntegralNumber(signed)
        if (signedIntegral) {
            if (signed.toLong() < 0L) {
                return false
            }
            return BigDecimal.valueOf(signed.toLong()).compareTo(unsignedToBigDecimal(unsigned)) == 0
        }
        return areClose(signed.toDouble(), unsignedToBigDecimal(unsigned).toDouble())
    }

    private fun isUnsignedNumber(value: Any): Boolean =
        value is UByte || value is UShort || value is UInt || value is ULong

    private fun isSignedIntegralNumber(value: Number): Boolean =
        value is Byte || value is Short || value is Int || value is Long

    private fun unsignedToBigDecimal(value: Any): BigDecimal =
        when (value) {
            is UByte -> BigDecimal(value.toString())
            is UShort -> BigDecimal(value.toString())
            is UInt -> BigDecimal(value.toString())
            is ULong -> BigDecimal(value.toString())
            else -> throw IllegalArgumentException("Unsupported unsigned number type: ${value::class}")
        }

    private fun formatUnsigned(unsigned: Any): String = applyWidth(unsignedToBigDecimal(unsigned).toPlainString())

    private fun formatComplex(complex: APLComplex): String {
        if (abs(complex.imaginary) <= currentContext().comparisonTolerance) {
            return formatScalarNumber(complex.real)
        }

        val real = formatNumber(complex.real)
        val imaginary = formatNumber(abs(complex.imaginary))
        val sign = if (complex.imaginary >= 0.0) "+" else "-"
        return applyWidth("$real$sign${imaginary}i")
    }

    private fun formatScalarNumber(number: Number): String = applyWidth(formatNumber(number))

    private fun formatNumber(number: Number): String {
        val asDouble = number.toDouble()
        if (!asDouble.isFinite()) {
            return when {
                asDouble.isNaN() -> "NaN"
                asDouble > 0 -> "Infinity"
                else -> "-Infinity"
            }
        }

        val value = when (number) {
            is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(number.toLong())
            is Float, is Double -> BigDecimal(number.toString())
            else -> BigDecimal(number.toString())
        }

        var formatted = value
        val precision = currentContext().printPrecision
        if (precision >= 0) {
            formatted = formatted.setScale(precision, RoundingMode.HALF_UP)
        }
        formatted = formatted.stripTrailingZeros()
        if (formatted.scale() < 0) {
            formatted = formatted.setScale(0, RoundingMode.UNNECESSARY)
        }
        return formatted.toPlainString()
    }

    private fun toBigDecimal(number: Number): BigDecimal =
        when (number) {
            is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(number.toLong())
            is Float, is Double -> BigDecimal(number.toString())
            else -> BigDecimal(number.toString())
        }

    private fun applyWidth(value: String): String {
        val width = currentContext().printWidth
        if (width <= value.length) {
            return value
        }
        return value.padStart(width)
    }
}
