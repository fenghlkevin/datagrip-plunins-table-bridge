import Foundation
import AVFoundation
import ImageIO
import CoreGraphics
import CoreVideo
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let asset = AVURLAsset(url: url)
let track = asset.tracks(withMediaType: .video).first!
let reader = try AVAssetReader(asset: asset)
let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
reader.add(output)
precondition(reader.startReading())
var count = 0
while output.copyNextSampleBuffer() != nil { count += 1 }
precondition(reader.status == .completed)
precondition(count == 672)
let duration = CMTimeGetSeconds(asset.duration)
precondition(abs(duration - 56) < 0.1)
precondition(track.naturalSize.width == 1280 && track.naturalSize.height == 800)
let generator = AVAssetImageGenerator(asset: asset)
generator.appliesPreferredTrackTransform = true
for second in [0,16,32,48,55] {
 let img = try generator.copyCGImage(at: CMTime(seconds: Double(second), preferredTimescale: 12), actualTime: nil)
 let target = URL(fileURLWithPath: "build/demo-video-\(second).png")
 let dest = CGImageDestinationCreateWithURL(target as CFURL, "public.png" as CFString, 1, nil)!
 CGImageDestinationAddImage(dest, img, nil)
 precondition(CGImageDestinationFinalize(dest))
}
let result: [String: Any] = ["frames":count,"durationSeconds":duration,"width":1280,"height":800,"sampleRead":"PASS","frameExtraction":"PASS","audioTracks":asset.tracks(withMediaType:.audio).count]
let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted,.sortedKeys])
try data.write(to: URL(fileURLWithPath: "docs/media/verification.json"))
print(String(data:data,encoding:.utf8)!)
