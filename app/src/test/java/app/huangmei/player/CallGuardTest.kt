package app.huangmei.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGuardTest {
    // 微信每次来电的通知 key 都一样
    private val key = "0|com.tencent.mm|41|null|10335"

    private fun call(at: Long, removed: Boolean = false) =
        CallGuard.Incoming(key = key, name = "张三", video = true, at = at, pi = null, removed = removed)

    @Test
    fun updateOfTheSameRingingCallIsIgnored() {
        assertTrue(CallGuard.isSameRingingCall(call(at = 1_000), key, now = 5_000))
    }

    @Test
    fun sameKeyAfterOldCallStoppedRingingIsANewCall() {
        // 2026-11-24 的问题：上一通没被清掉，这一通 key 一样被当成旧的
        assertFalse(CallGuard.isSameRingingCall(call(at = 1_000), key, now = 1_000 + CallGuard.RING_MS + 1))
    }

    @Test
    fun sameKeyAfterNotificationWasWithdrawnIsANewCall() {
        assertFalse(CallGuard.isSameRingingCall(call(at = 1_000, removed = true), key, now = 5_000))
    }

    @Test
    fun noRecordOrOtherKeyIsANewCall() {
        assertFalse(CallGuard.isSameRingingCall(null, key, now = 5_000))
        assertFalse(CallGuard.isSameRingingCall(call(at = 1_000), "0|com.tencent.mm|42|null|10335", now = 5_000))
    }

    @Test
    fun parseInvite() {
        assertEquals("张三" to true, CallGuard.parseInvite("张三邀请你视频通话"))
        assertEquals("小明" to false, CallGuard.parseInvite("小明邀请你语音通话"))
        assertNull(CallGuard.parseInvite("视频通话中"))
        assertNull(CallGuard.parseInvite("[视频通话]"))
    }

    @Test
    fun missedCallRecordEndsTheCall() {
        // 2026-11-24 第二次测试：对方挂断后微信发的消息通知
        assertTrue(CallGuard.isCallRecord("张三", "[2条]张三: [视频通话]", "张三"))
        assertTrue(CallGuard.isCallRecord("张三", "[视频通话]", "张三"))
        assertTrue(CallGuard.isCallRecord("张三", "[语音通话]", "张三"))
        // 来电本身的通知、通话中的通知、别人的记录都不算
        assertFalse(CallGuard.isCallRecord("微信", "张三邀请你视频通话", "张三"))
        assertFalse(CallGuard.isCallRecord("张三", "视频通话中", "张三"))
        assertFalse(CallGuard.isCallRecord("小明", "[视频通话]", "张三"))
    }
}
