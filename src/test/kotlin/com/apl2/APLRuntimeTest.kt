package com.apl2

import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class APLRuntimeTest {
    @AfterTest
    fun tearDown() {
        APLRuntime.destroyContext()
    }

    @Test
    fun contextStackLifecycle() {
        val defaultContext = APLRuntime.createContext()
        assertEquals(0, defaultContext.indexOrigin)
        assertEquals(0, defaultContext.printWidth)
        assertEquals(-1, defaultContext.printPrecision)
        assertEquals(1e-15, defaultContext.comparisonTolerance)
        assertEquals(APLContext.DEFAULT, APLRuntime.currentContext())

        val outer = APLContext(indexOrigin = 1, comparisonTolerance = 0.01)
        val inner = outer.copy(printPrecision = 3)

        APLRuntime.pushContext(outer)
        assertEquals(outer, APLRuntime.currentContext())

        APLRuntime.pushContext(inner)
        assertEquals(inner, APLRuntime.currentContext())

        assertEquals(inner, APLRuntime.popContext())
        assertEquals(outer, APLRuntime.currentContext())
        assertEquals(outer, APLRuntime.popContext())
        assertEquals(APLContext.DEFAULT, APLRuntime.currentContext())

        try {
            APLRuntime.popContext()
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun arrayIndexingRespectsRuntimeIndexOrigin() {
        APLRuntime.pushContext(APLContext(indexOrigin = 1))

        val vector = APLArray(listOf(10, 20, 30))
        assertEquals(10, vector.getElement(1))
        assertEquals(30, vector.getElement(3))

        val matrix = APLArray(listOf(1, 2, 3, 4), intArrayOf(2, 2))
        assertEquals(2, matrix.getElement(1, 2))
        assertTrue(APLRuntime.toOriginIndices(intArrayOf(0, 1)).contentEquals(intArrayOf(1, 2)))
    }

    @Test
    fun formattingRespectsPrecisionAndWidth() {
        APLRuntime.pushContext(APLContext(printPrecision = 2, printWidth = 6, comparisonTolerance = 0.01))

        assertEquals("  3.14", APLRuntime.format(3.14159))
        assertEquals("  2.35-  6.79i", APLRuntime.format(APLComplex(2.345, -6.789)))
        assertEquals("  2.35", APLRuntime.format(APLComplex(2.345, 0.0001)))
        assertEquals("123.46", APLRuntime.format(123.456))
        assertEquals("[     1,   2.35]", APLRuntime.format(APLArray(listOf(1, 2.345))))
        assertEquals("   NaN", APLRuntime.format(Double.NaN))
        assertEquals("Infinity", APLRuntime.format(Double.POSITIVE_INFINITY))
    }

    @Test
    fun equalityRespectsComparisonTolerance() {
        assertFalse(APLRuntime.valuesEqual(1.0, 1.005))

        APLRuntime.pushContext(APLContext(comparisonTolerance = 0.01))

        assertTrue(APLRuntime.valuesEqual(1.0, 1.005))
        assertTrue(APLRuntime.valuesEqual(APLComplex(1.0, 2.0), APLComplex(1.005, 1.995)))
        assertTrue(APLRuntime.valuesEqual(APLArray(listOf(1.0, 2.0)), APLArray(listOf(1.005, 1.995))))
        assertFalse(
            APLRuntime.valuesEqual(
                APLArray(listOf(1, 2, 3, 4), intArrayOf(4)),
                APLArray(listOf(1, 2, 3, 4), intArrayOf(2, 2)),
            ),
        )
        assertFalse(APLRuntime.valuesEqual(1, 2))
        assertFalse(APLRuntime.valuesEqual(true, false))
    }

    @Test
    fun contextIsThreadLocal() {
        APLRuntime.pushContext(APLContext(indexOrigin = 1))
        var childContextOrigin = -1

        val worker = thread {
            childContextOrigin = APLRuntime.currentContext().indexOrigin
        }
        worker.join()

        assertEquals(0, childContextOrigin)
        assertEquals(1, APLRuntime.currentContext().indexOrigin)
    }
}
