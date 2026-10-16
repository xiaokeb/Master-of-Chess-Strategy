package com.masterofchessstrategy.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSourceLicensesAssetTest {
    @Test
    fun dependencySourceCopyPreservesEveryByte() {
        val source = ByteArray(131_071) { index -> (index * 31).toByte() }
        val destination = ByteArrayOutputStream()

        copyLegalAsset(
            openSource = { ByteArrayInputStream(source) },
            openDestination = { destination },
        )

        assertArrayEquals(source, destination.toByteArray())
    }

    @Test
    fun unavailableDestinationIsReportedAsFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            copyLegalAsset(
                openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                openDestination = { null },
            )
        }
    }
}
