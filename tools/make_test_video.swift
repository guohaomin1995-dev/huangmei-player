// 生成无声测试视频（只有画面，没有音轨），可选同名海报。
// 用法：swiftc -O tools/make_test_video.swift -o /tmp/mkvid
//       /tmp/mkvid <输出.mp4> <标题> <副标题> <秒数> [海报.jpg]
// 例：  /tmp/mkvid 03-打猪草.mp4 打猪草 "" 20 03-打猪草.jpg
// 故意不支持生成声音：调试时任何设备都不能出声。
import AVFoundation
import AppKit
import CoreText

let args = CommandLine.arguments
guard args.count >= 5, let seconds = Int(args[4]), seconds > 0 else {
    print("用法: mkvid <输出.mp4> <标题> <副标题> <秒数> [海报.jpg]")
    exit(2)
}
let outURL = URL(fileURLWithPath: args[1])
let title = args[2]
let subtitle = args[3]
let posterPath: String? = args.count > 5 ? args[5] : nil

let W = 1280, H = 720
let hue = CGFloat(title.unicodeScalars.reduce(0) { $0 + Int($1.value) } % 360) / 360
let gold = NSColor(calibratedRed: 0.95, green: 0.76, blue: 0.31, alpha: 1)

func makeContext(data: UnsafeMutableRawPointer?, bytesPerRow: Int) -> CGContext {
    CGContext(data: data, width: W, height: H, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
              space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
}

func drawText(_ ctx: CGContext, _ s: String, size: CGFloat, y: CGFloat, color: NSColor, font: String = "PingFangSC-Semibold") {
    let f = NSFont(name: font, size: size) ?? NSFont.systemFont(ofSize: size)
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: [.font: f, .foregroundColor: color]))
    let width = CTLineGetTypographicBounds(line, nil, nil, nil)
    ctx.textPosition = CGPoint(x: (CGFloat(W) - CGFloat(width)) / 2, y: y)
    CTLineDraw(line, ctx)
}

func drawBackground(_ ctx: CGContext, brightness: CGFloat) {
    let top = NSColor(hue: hue, saturation: 0.7, brightness: brightness, alpha: 1).cgColor
    let bottom = NSColor(hue: hue, saturation: 0.8, brightness: 0.12, alpha: 1).cgColor
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [top, bottom] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: CGFloat(H)), end: .zero, options: [])
}

func drawFrame(_ ctx: CGContext, second: Int) {
    drawBackground(ctx, brightness: 0.35)
    drawText(ctx, title, size: 130, y: 400, color: .white)
    if !subtitle.isEmpty { drawText(ctx, subtitle, size: 50, y: 320, color: NSColor(white: 0.85, alpha: 1), font: "PingFangSC-Regular") }
    drawText(ctx, String(format: "测试片 · 第 %d:%02d / %d:%02d", second / 60, second % 60, seconds / 60, seconds % 60),
             size: 56, y: 150, color: gold)
    ctx.setFillColor(NSColor(white: 1, alpha: 0.2).cgColor)
    ctx.fill(CGRect(x: 100, y: 90, width: CGFloat(W - 200), height: 14))
    ctx.setFillColor(gold.cgColor)
    ctx.fill(CGRect(x: 100, y: 90, width: CGFloat(W - 200) * CGFloat(second) / CGFloat(max(1, seconds - 1)), height: 14))
}

if let posterPath {
    let ctx = makeContext(data: nil, bytesPerRow: 0)
    drawBackground(ctx, brightness: 0.55)
    ctx.setStrokeColor(gold.cgColor)
    ctx.setLineWidth(16)
    ctx.stroke(CGRect(x: 40, y: 40, width: W - 80, height: H - 80))
    drawText(ctx, title, size: 170, y: 300, color: gold)
    drawText(ctx, "海报", size: 48, y: 150, color: .white, font: "PingFangSC-Regular")
    let jpeg = NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .jpeg, properties: [.compressionFactor: 0.85])!
    try jpeg.write(to: URL(fileURLWithPath: posterPath))
}

try? FileManager.default.removeItem(at: outURL)
let writer = try AVAssetWriter(outputURL: outURL, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: W,
    AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 300_000, AVVideoMaxKeyFrameIntervalKey: 2],
])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
    kCVPixelBufferWidthKey as String: W,
    kCVPixelBufferHeightKey as String: H,
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

// 每秒一帧，画面上显示进度，方便看断点续播是否对
for second in 0..<seconds {
    while !input.isReadyForMoreMediaData { usleep(1000) }
    var pb: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
    CVPixelBufferLockBaseAddress(pb!, [])
    drawFrame(makeContext(data: CVPixelBufferGetBaseAddress(pb!), bytesPerRow: CVPixelBufferGetBytesPerRow(pb!)), second: second)
    CVPixelBufferUnlockBaseAddress(pb!, [])
    adaptor.append(pb!, withPresentationTime: CMTime(value: CMTimeValue(second), timescale: 1))
}
input.markAsFinished()
writer.endSession(atSourceTime: CMTime(value: CMTimeValue(seconds), timescale: 1))
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else {
    print("失败: \(String(describing: writer.error))")
    exit(1)
}
print("ok \(outURL.lastPathComponent)（无音轨）")
