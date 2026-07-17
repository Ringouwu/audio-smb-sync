package com.fde.audiosmbsync.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTimeResolverTest {
    @Test fun resolvesCompactTimestamp() {
        val result = RecordingTimeResolver.resolve("张三@189_20260717153025.m4a", 0, "", false)
        assertNotNull(result)
        assertEquals("20260717153025", RecordingTimeResolver.formatForUpload(result!!.epochMillis))
    }

    @Test fun resolvesChineseTimestamp() {
        val result = RecordingTimeResolver.resolve("通话_2026年7月17日15时30分25秒.mp3", 0, "", false)
        assertNotNull(result)
        assertEquals("20260717153025", RecordingTimeResolver.formatForUpload(result!!.epochMillis))
    }

    @Test fun rejectsUnknownNameWithoutFallback() {
        assertNull(RecordingTimeResolver.resolve("未知录音.m4a", 0, "", false))
    }
}
