package com.moon.videomerger.editor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 草稿序列化往返自测：锁定草稿 JSON 格式，防止字段遗漏导致恢复丢数据。
 */
class DraftSerializationTest {

    private fun sampleProject(): EditorProject {
        val keyframes = listOf(
            PipKeyframe(time = 0.5, x = 0.1, y = 0.2),
            PipKeyframe(time = 1.5, x = 0.4, y = 0.6),
        )
        val main = Clip(
            mediaPath = "/data/editor_media/input_1.mp4",
            mediaName = "input_1.mp4",
            mediaDuration = 12.0,
            width = 1088,
            height = 1920,
            hasAudio = true,
            trimStart = 1.0,
            trimEnd = 10.0,
            speed = 1.5,
            reversed = true,
            volume = 0.8,
            audioFadeIn = 0.3,
            pitchShift = 1.2,
            noiseReduction = true,
            rotation = 90,
            hflip = true,
            filterPreset = FilterPreset.VINTAGE,
            brightness = 0.1,
            textOverlay = "hello",
            textSize = 40,
            textColor = "#FF0000",
            transition = TransitionEffect.FADE,
            transitionDuration = 1.0,
            blurBgEnabled = true,
        )
        val pip = Clip(
            mediaPath = "/data/editor_media/sticker.png",
            mediaName = "😀",
            mediaDuration = 300.0,
            width = 256,
            height = 256,
            hasAudio = false,
            trimEnd = 3.0,
            timelineStart = 2.0,
            pipEnabled = true,
            isImage = true,
            pipX = 0.2,
            pipY = 0.3,
            pipWidth = 0.25,
            pipOpacity = 0.7,
            pipShape = PipShape.CIRCLE,
            pipKeyframes = keyframes,
        )
        return EditorProject(
            name = "项目 测试",
            canvasWidth = 1088,
            canvasHeight = 1920,
            fps = 30,
            tracks = mutableListOf(
                Track(type = TrackType.MAIN, clips = mutableListOf(relayoutMainTrackClips(listOf(main)).first())),
                Track(type = TrackType.PICTURE, clips = mutableListOf(pip), muted = true),
            ),
            subtitles = listOf(
                Subtitle(text = "字幕一", startTime = 1.0, endTime = 2.5),
                Subtitle(text = "字幕二", startTime = 3.0, endTime = 4.0),
            ),
        )
    }

    @Test
    fun 序列化往返无损() {
        val original = sampleProject()
        val restored = DraftStore.decodeProject(DraftStore.encodeProject(original))

        assertNotNull(restored)
        restored!!
        assertEquals(original.name, restored.name)
        assertEquals(original.canvasWidth, restored.canvasWidth)
        assertEquals(original.tracks.size, restored.tracks.size)
        assertEquals(original.subtitles, restored.subtitles)

        val origMain = original.mainTrack!!.clips.first()
        val newMain = restored.mainTrack!!.clips.first()
        assertEquals(origMain.id, newMain.id)
        assertEquals(origMain.trimStart, newMain.trimStart, 1e-9)
        assertEquals(origMain.trimEnd, newMain.trimEnd, 1e-9)
        assertEquals(origMain.speed, newMain.speed, 1e-9)
        assertEquals(origMain.reversed, newMain.reversed)
        assertEquals(origMain.filterPreset, newMain.filterPreset)
        assertEquals(origMain.transition, newMain.transition)
        assertEquals(origMain.timelineStart, newMain.timelineStart, 1e-9)

        val origPip = original.tracks[1].clips.first()
        val newPip = restored.tracks[1].clips.first()
        assertEquals(origPip.pipShape, newPip.pipShape)
        assertEquals(origPip.pipKeyframes, newPip.pipKeyframes)
        assertEquals(origPip.isImage, newPip.isImage)
    }

    @Test
    fun 损坏的JSON返回null不抛异常() {
        assertNull(DraftStore.decodeProject("not a json {"))
        assertNull(DraftStore.decodeProject(""))
    }

    @Test
    fun 空项目可往返() {
        val empty = EditorProject()
        val restored = DraftStore.decodeProject(DraftStore.encodeProject(empty))
        assertNotNull(restored)
        assertEquals(empty.name, restored!!.name)
    }
}
