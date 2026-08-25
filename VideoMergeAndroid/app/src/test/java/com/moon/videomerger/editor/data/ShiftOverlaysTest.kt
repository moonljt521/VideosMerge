package com.moon.videomerger.editor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主轨增删后叠加元素（字幕/画中画）联动平移的自测。
 */
class ShiftOverlaysTest {

    private fun project(
        subtitles: List<Subtitle>,
        pipClips: List<Clip>
    ): EditorProject {
        val main = Track(type = TrackType.MAIN, clips = mutableListOf(clipOf(10.0)))
        val tracks = mutableListOf<Track>(main)
        if (pipClips.isNotEmpty()) {
            tracks.add(Track(type = TrackType.PICTURE, clips = pipClips.toMutableList()))
        }
        return EditorProject(tracks = tracks, subtitles = subtitles)
    }

    private fun clipOf(duration: Double, start: Double = 0.0) = Clip(
        mediaPath = "/tmp/x.mp4",
        mediaName = "x",
        mediaDuration = duration,
        width = 100,
        height = 100,
        hasAudio = false,
        trimEnd = duration,
        timelineStart = start,
    )

    @Test
    fun 锚点之后的字幕整体前移() {
        val p = project(
            subtitles = listOf(
                Subtitle(text = "前", startTime = 1.0, endTime = 2.0),
                Subtitle(text = "中", startTime = 4.5, endTime = 5.5),
                Subtitle(text = "后", startTime = 7.0, endTime = 9.0),
            ),
            pipClips = emptyList()
        )
        // 删除 [3, 6]，主轨缩短 3s，锚点 = 3
        val out = shiftOverlaysAfter(p, anchor = 3.0, delta = -3.0)

        assertEquals(1.0, out.subtitles[0].startTime, 1e-9) // 锚点前不动
        assertEquals(1.5, out.subtitles[1].startTime, 1e-9) // 4.5-3
        assertEquals(2.5, out.subtitles[1].endTime, 1e-9)
        assertEquals(4.0, out.subtitles[2].startTime, 1e-9)
    }

    @Test
    fun 锚点之后的画中画片段前移且不小于0() {
        val p = project(
            subtitles = emptyList(),
            pipClips = listOf(
                clipOf(3.0, start = 1.0),
                clipOf(3.0, start = 5.0),
            )
        )
        // 删除 [0, 4]：锚点 0 之后全部前移 4s；起点 1.0 的贴到 0
        val out = shiftOverlaysAfter(p, anchor = 0.0, delta = -4.0)

        val pipTrack = out.tracks.first { it.type == TrackType.PICTURE }
        assertEquals(0.0, pipTrack.clips[0].timelineStart, 1e-9)
        assertEquals(1.0, pipTrack.clips[1].timelineStart, 1e-9)
    }

    @Test
    fun 主轨自身不平移() {
        val mainClip = clipOf(10.0, start = 0.0)
        val p = EditorProject(
            tracks = mutableListOf(Track(type = TrackType.MAIN, clips = mutableListOf(mainClip)))
        )
        val out = shiftOverlaysAfter(p, anchor = 2.0, delta = -1.0)
        assertEquals(0.0, out.tracks[0].clips[0].timelineStart, 1e-9)
    }

    @Test
    fun delta为0时原样返回() {
        val p = project(
            subtitles = listOf(Subtitle(text = "a", startTime = 3.0, endTime = 4.0)),
            pipClips = emptyList()
        )
        assertEquals(p, shiftOverlaysAfter(p, anchor = 2.0, delta = 0.0))
    }

    @Test
    fun 跨越锚点的元素保持绝对时间() {
        val sub = Subtitle(text = "跨", startTime = 2.0, endTime = 8.0)
        val p = project(subtitles = listOf(sub), pipClips = emptyList())
        val out = shiftOverlaysAfter(p, anchor = 4.0, delta = -2.0)
        // 起点 2.0 < 锚点 4.0 → 不动（v1 规则）
        assertEquals(2.0, out.subtitles[0].startTime, 1e-9)
        assertEquals(8.0, out.subtitles[0].endTime, 1e-9)
    }
}
