package com.moon.videomerger.linkparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分享文案 → 解析平台的路由单元测试（对齐 iOS 的 LinkParserRegistryTests）。
 *
 * 只验证纯逻辑：正则识别与注册表分发、体积闸门策略，不触网。
 */
class LinkParserRegistryTest {

    @Test
    fun `抖音分享文案路由到抖音解析器`() {
        val text = "7.43 复制打开抖音，看看作品 https://v.douyin.com/iRNBho6/ 复制此链接，打开Dou音搜索"
        val routing = LinkParserRegistry.detect(text)
        assertEquals("douyin", routing?.parser?.id)
        assertEquals("https://v.douyin.com/iRNBho6/", routing?.shareUrl)
    }

    @Test
    fun `抖音短码中的下划线与连字符不被截断`() {
        val link = "https://v.douyin.com/aB_c-D/"
        assertEquals(link, DouyinLinkParser().extractShareUrl(link))
    }

    @Test
    fun `B站短链与直链都路由到B站解析器`() {
        val short = "复制打开视频站，看看这个 https://b23.tv/aBcDeF 的内容"
        assertEquals("bilibili", LinkParserRegistry.detect(short)?.parser?.id)
        assertEquals("https://b23.tv/aBcDeF", LinkParserRegistry.detect(short)?.shareUrl)

        val direct = "https://www.bilibili.com/video/BV1SveU6GExV/?spm_id_from=333.999"
        assertEquals("bilibili", LinkParserRegistry.detect(direct)?.parser?.id)
        assertEquals(direct, LinkParserRegistry.detect(direct)?.shareUrl)
    }

    @Test
    fun `X分享文案路由到X解析器且不吃掉查询参数`() {
        val text = "看看我在X上发现的好内容 https://x.com/macrotradecn/status/2101664353745580481?s=20"
        val routing = LinkParserRegistry.detect(text)
        assertEquals("twitter", routing?.parser?.id)
        assertEquals("https://x.com/macrotradecn/status/2101664353745580481", routing?.shareUrl)
    }

    @Test
    fun `X旧域名认领，站内其他路径不认领`() {
        assertEquals("https://twitter.com/foo/status/123",
            TwitterLinkParser().extractShareUrl("https://twitter.com/foo/status/123"))
        assertNull(TwitterLinkParser().extractShareUrl("https://x.com/privacy"))
        assertNull(TwitterLinkParser().extractShareUrl("https://x.com/foo/bar/123"))
        assertNull(LinkParserRegistry.detect("https://x.com/home"))
    }

    @Test
    fun `三个平台互不抢同一份文案`() {
        assertEquals("douyin", LinkParserRegistry.detect(
            "看看 https://v.douyin.com/iRNBho6/ 和 https://www.bilibili.com/video/BV1SveU6GExV")?.parser?.id)
        assertEquals("bilibili", LinkParserRegistry.detect(
            "【微电影】https://www.bilibili.com/video/BV1SveU6GExV")?.parser?.id)
    }

    @Test
    fun `未接入平台与无链接文案均不路由`() {
        assertNull(LinkParserRegistry.detect("https://www.kuaishou.com/short-video/3xwabcdefg"))
        assertNull(LinkParserRegistry.detect("https://v.xiaohongshu.com/a/1234567890"))
        assertNull(LinkParserRegistry.detect("这里没有任何链接"))
        assertNull(LinkParserRegistry.detect(""))
    }

    @Test
    fun `体积闸门上限固定为 150MB`() {
        // VM 用「严格大于」判定，恰好等于上限放行；探不到体积（抖音 feed / X HEAD 失败）则为 null，闸门跳过
        assertEquals(150_000_000L, LinkParsePolicy.MAX_DOWNLOAD_BYTES)
    }

    @Test
    fun `体积文案按 MB 展示`() {
        assertEquals("180MB", LinkParsePolicy.human(179_802_771))
        assertEquals("12MB", LinkParsePolicy.human(11_598_169))
        assertEquals("150MB", LinkParsePolicy.human(LinkParsePolicy.MAX_DOWNLOAD_BYTES))
    }
}
