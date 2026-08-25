package com.moon.videomerger.editor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 转场重叠区（实时预览用）判定自测。
 */
class TransitionZoneTest {

    private fun clip(
        duration: Double,
        transition: TransitionEffect = TransitionEffect.NONE,
        transDur: Double = 0.8
    ) = Clip(
        mediaPath = "/tmp/x.mp4",
        mediaName = "x",
        mediaDuration = duration,
        width = 100,
        height = 100,
        hasAudio = false,
        trimEnd = duration,
        transition = transition,
        transitionDuration = transDur,
    )

    @Test
    fun 播放头在重叠区内能找到转场区() {
        // a [0,5] fade 1.0 → b 从 4.0 开始，重叠区 [4.0, 5.0]
        val laid = relayoutMainTrackClips(
            listOf(clip(5.0, TransitionEffect.FADE, 1.0), clip(7.0))
        )
        val zone = findTransitionZone(laid, 4.5)
        assertNotNull(zone)
        assertEquals(4.0, zone!!.start, 1e-9)
        assertEquals(5.0, zone.end, 1e-9)
        assertEquals(0.5, zone.progress(4.5), 1e-9)
    }

    @Test
    fun 重叠区外返回null() {
        val laid = relayoutMainTrackClips(
            listOf(clip(5.0, TransitionEffect.FADE, 1.0), clip(7.0))
        )
        assertNull(findTransitionZone(laid, 3.9))   // 区间前
        assertNull(findTransitionZone(laid, 5.01))  // 区间后
    }

    @Test
    fun NONE转场不构成预览区() {
        val laid = relayoutMainTrackClips(listOf(clip(5.0), clip(7.0)))
        // NONE 重叠只有 0.01s，低于预览阈值
        assertNull(findTransitionZone(laid, laid[1].timelineStart + 0.005))
    }

    @Test
    fun 进度线性且钳制到01() {
        val a = clip(5.0, TransitionEffect.FADE, 1.0)
        val b = clip(7.0)
        val laid = relayoutMainTrackClips(listOf(a, b))
        val zone = findTransitionZone(laid, 4.25)!!
        assertEquals(0.25, zone.progress(4.25), 1e-9)
        assertEquals(0.0, zone.progress(3.0), 1e-9)  // 越界钳制
        assertEquals(1.0, zone.progress(6.0), 1e-9)
    }

    @Test
    fun 多段连续转场各自独立成区() {
        val laid = relayoutMainTrackClips(
            listOf(
                clip(5.0, TransitionEffect.FADE, 1.0),
                clip(5.0, TransitionEffect.FADE, 1.0),
                clip(5.0),
            )
        )
        assertNotNull(findTransitionZone(laid, laid[1].timelineStart + 0.5))
        assertNull(findTransitionZone(laid, laid[2].timelineStart - 2.0)) // b 与 c 无转场
    }
}
