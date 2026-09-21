//
//  LinkParserRegistryTests.swift
//  VideoMergerTests
//
//  去水印解析层的数据流第一环：分享文案 → 命中哪个平台。
//  不触网，只验证 LinkParserRegistry.detect 与各解析器的 extractShareURL。
//

import XCTest
@testable import VideoMerger

final class LinkParserRegistryTests: XCTestCase {

    func test抖音分享文案路由到抖音解析器() {
        let text = "7.43 复制打开抖音，看看作品 https://v.douyin.com/iRNBho6/ 复制此链接，打开Dou音搜索"
        let match = LinkParserRegistry.detect(in: text)
        XCTAssertEqual(match?.parser.id, "douyin")
        XCTAssertEqual(match?.shareURL, "https://v.douyin.com/iRNBho6/")
    }

    func test短码中的下划线与连字符不被截断() {
        let link = "https://v.douyin.com/aB_c-D/"
        XCTAssertEqual(DouyinLinkParser().extractShareURL(from: link), link)
    }

    func test长链接兜底匹配任意抖音域名() {
        let text = "看看这个 https://www.iesdouyin.com/share/video/1234567890/?region=CN 视频"
        XCTAssertEqual(DouyinLinkParser().extractShareURL(from: text),
                       "https://www.iesdouyin.com/share/video/1234567890/?region=CN")
    }

    func test未接入平台与无链接文案均不路由() {
        XCTAssertNil(LinkParserRegistry.detect(in: "https://www.kuaishou.com/short-video/3xwabcdefg"))
        XCTAssertNil(LinkParserRegistry.detect(in: "这里没有任何链接"))
        XCTAssertNil(LinkParserRegistry.detect(in: ""))
    }

    // MARK: - 第二平台

    func test视频站短链路由到第二解析器() {
        let text = "复制打开视频站，看看这个 https://b23.tv/aBcDeF 的内容"
        let match = LinkParserRegistry.detect(in: text)
        XCTAssertEqual(match?.parser.id, "bilibili")
        XCTAssertEqual(match?.shareURL, "https://b23.tv/aBcDeF")
    }

    func test视频站直链带编号时不再跟随短链() {
        let link = "https://www.bilibili.com/video/BV1SveU6GExV/?spm_id_from=333.999"
        let match = LinkParserRegistry.detect(in: link)
        XCTAssertEqual(match?.parser.id, "bilibili")
        XCTAssertEqual(match?.shareURL, link)
    }

    func test两个平台互不抢文案() {
        let douyin = "7.43 看看作品 https://v.douyin.com/iRNBho6/ 打开Dou音搜索"
        XCTAssertEqual(LinkParserRegistry.detect(in: douyin)?.parser.id, "douyin")
        let bili = "【微电影】https://www.bilibili.com/video/BV1SveU6GExV"
        XCTAssertEqual(LinkParserRegistry.detect(in: bili)?.parser.id, "bilibili")
    }

    // MARK: - 第三平台

    func testX分享文案路由到第三解析器() {
        let text = "看看我在X上发现的好内容 https://x.com/macrotradecn/status/2101664353745580481?s=20"
        let match = LinkParserRegistry.detect(in: text)
        XCTAssertEqual(match?.parser.id, "twitter")
        // 查询参数不该被算进链接本身
        XCTAssertEqual(match?.shareURL, "https://x.com/macrotradecn/status/2101664353745580481")
    }

    func testX旧域名同样认领() {
        XCTAssertEqual(TwitterLinkParser().extractShareURL(from: "https://twitter.com/foo/status/123"),
                       "https://twitter.com/foo/status/123")
    }

    func testX站内非作品路径不认领() {
        XCTAssertNil(TwitterLinkParser().extractShareURL(from: "https://x.com/privacy"))
        XCTAssertNil(TwitterLinkParser().extractShareURL(from: "https://x.com/foo/bar/123"))
        XCTAssertNil(LinkParserRegistry.detect(in: "https://x.com/home")?.parser.id)
    }
}
