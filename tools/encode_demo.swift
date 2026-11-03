import Foundation
import AVFoundation
import CoreGraphics
import ImageIO
import CoreVideo

// Encodes annotated production-UI frames. No desktop capture or external upload.
let args = CommandLine.arguments
if args.count != 4 { fatalError("usage: encode_demo frames-directory output.mp4 fps") }
let folder = URL(fileURLWithPath: args[1]), output = URL(fileURLWithPath: args[2])
let fps = Int32(args[3])!
let frames = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { $0.pathExtension == "jpg" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
if frames.isEmpty { fatalError("no frames") }
try? FileManager.default.removeItem(at: output)
let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 1280, AVVideoHeightKey: 800, AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 2_500_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel]])
input.expectsMediaDataInRealTime = false
let adapter = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: 1280, kCVPixelBufferHeightKey as String: 800, kCVPixelBufferCGImageCompatibilityKey as String: true, kCVPixelBufferCGBitmapContextCompatibilityKey as String: true])
writer.add(input)
if !writer.startWriting() { fatalError("start: \(String(describing: writer.error))") }
writer.startSession(atSourceTime: .zero)
for (index, url) in frames.enumerated() {
    while !input.isReadyForMoreMediaData { if writer.status == .failed { fatalError("encode: \(String(describing: writer.error))") }; Thread.sleep(forTimeInterval: 0.005) }
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("bad image") }
    var buffer: CVPixelBuffer?
    let status = CVPixelBufferPoolCreatePixelBuffer(nil, adapter.pixelBufferPool!, &buffer)
    guard status == kCVReturnSuccess, let pixels = buffer else { fatalError("pixel buffer") }
    CVPixelBufferLockBaseAddress(pixels, [])
    let context = CGContext(data: CVPixelBufferGetBaseAddress(pixels), width: 1280, height: 800, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixels), space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
    context.draw(img, in: CGRect(x: 0, y: 0, width: 1280, height: 800))
    CVPixelBufferUnlockBaseAddress(pixels, [])
    if !adapter.append(pixels, withPresentationTime: CMTime(value: Int64(index), timescale: fps)) { fatalError("append: \(String(describing: writer.error))") }
}
input.markAsFinished()
let finish = DispatchSemaphore(value: 0)
writer.finishWriting { finish.signal() }
finish.wait()
if writer.status != .completed { fatalError("finish: \(String(describing: writer.error))") }
let asset = AVURLAsset(url: output)
let duration = Double(frames.count) / Double(fps)
print("Encoded \(frames.count) frames; \(duration)s; 1280x800 H.264 MP4")
