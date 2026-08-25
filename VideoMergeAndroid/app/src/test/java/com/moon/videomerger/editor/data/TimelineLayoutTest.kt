package com.moon.videomerger.editor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时间轴布局（转场 = 重叠）数学自测。
 * 保证时间轴布局与导出 xfade 的重叠时长严格一致。
 */
class TimelineLayoutTest {

    private fun clip(
        duration: Double,
        transition: TransitionEffect = TransitionEffect.NONE,
        transDur: Double = 0.8,
        speed: Double = 1.0
    ) = Clip(
        mediaPath = "/tmp/x.mp4",
        mediaName = "x",
        mediaDuration = duration,
        width = 1088,
        height = 1920,
        hasAudio = true,
        trimEnd = duration,
        speed = speed,
        transition = transition,
        transitionDuration = transDur,
    )

    @Test
    fun 无转场时按极短重叠拼接() {
        // NONE 也保留 0.01s 重叠（与导出 xfade 链的衔接淡变一致）
        val laid = relayoutMainTrackClips(listOf(clip(5.0), clip(7.0), clip(3.0)))
        assertEquals(0.0, laid[0].timelineStart, 1e-9)
        assertEquals(5.0 - TRANSITION_NONE_OVERLAP, laid[1].timelineStart, 1e-9)
        assertEquals(12.0 - TRANSITION_NONE_OVERLAP * 2, laid[2].timelineStart, 1e-9)
        assertEquals(15.0 - TRANSITION_NONE_OVERLAP * 2, laid[2].timelineEnd, 1e-9)
    }

    @Test
    fun 转场处片段重叠且总时长缩短() {
        val laid = relayoutMainTrackClips(
            listOf(clip(5.0, TransitionEffect.FADE, 0.8), clip(7.0))
        )
        // 下一片段提前转场时长开始
        assertEquals(5.0 - 0.8, laid[1].timelineStart, 1e-9)
        // 总时长 = 各片段时长之和 - 重叠
        assertEquals(5.0 + 7.0 - 0.8, laid[1].timelineEnd, 1e-9)
    }

    @Test
    fun NONE转场使用极短重叠保持与导出一致() {
        val a = clip(5.0)
        val b = clip(7.0)
        assertEquals(TRANSITION_NONE_OVERLAP, effectiveTransitionOverlap(a, b), 1e-9)
        val laid = relayoutMainTrackClips(listOf(a, b))
        assertEquals(5.0 - TRANSITION_NONE_OVERLAP, laid[1].timelineStart, 1e-9)
    }

    @Test
    fun 转场时长不超过较短片段的八成() {
        val a = clip(1.0, TransitionEffect.FADE, 3.0)
        val b = clip(10.0)
        assertEquals(0.8, effectiveTransitionOverlap(a, b), 1e-9)

        val laid = relayoutMainTrackClips(listOf(a, b))
        assertEquals(1.0 - 0.8, laid[1].timelineStart, 1e-9)
    }

    @Test
    fun 链式转场累计重叠() {
        val laid = relayoutMainTrackClips(
            listOf(
                clip(5.0, TransitionEffect.FADE, 1.0),
                clip(5.0, TransitionEffect.FADE, 1.0),
                clip(5.0)
            )
        )
        assertEquals(4.0, laid[1].timelineStart, 1e-9)
        assertEquals(8.0, laid[2].timelineStart, 1e-9)
        assertEquals(13.0, laid[2].timelineEnd, 1e-9) // 15 - 2 个转场重叠
    }

    @Test
    fun 变速影响重叠上限与总时长() {
        // timelineDuration = 4/2 = 2 → 上限 = 2*0.8 = 1.6
        val a = clip(4.0, TransitionEffect.FADE, 2.0, speed = 2.0)
        val b = clip(10.0)
        assertEquals(1.6, effectiveTransitionOverlap(a, b), 1e-9)

        val laid = relayoutMainTrackClips(listOf(a, b))
        assertEquals(2.0 - 1.6, laid[1].timelineStart, 1e-9)
        assertEquals(2.0 - 1.6 + 10.0, laid[1].timelineEnd, 1e-9)
    }

    @Test
    fun 空列表与单片段安全() {
        assertEquals(0, relayoutMainTrackClips(emptyList()).size)
        val single = relayoutMainTrackClips(listOf(clip(6.0)))
        assertEquals(0.0, single[0].timelineStart, 1e-9)
        assertEquals(6.0, single[0].timelineEnd, 1e-9)
    }
}
