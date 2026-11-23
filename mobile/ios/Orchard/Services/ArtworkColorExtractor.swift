import SwiftUI
import CoreImage
import UIKit

// MARK: - Dynamic Artwork Color Extractor for Apple Music Glow

public final class ArtworkColorExtractor {
    public static let shared = ArtworkColorExtractor()
    private var colorCache: [URL: (primary: Color, secondary: Color, darkAccent: Color)] = [:]
    private let context = CIContext(options: [.workingColorSpace: kCFNull as Any])

    private init() {}

    public func extractColors(from url: URL?) async -> (primary: Color, secondary: Color, darkAccent: Color) {
        guard let url = url else {
            return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
        }

        if let cached = colorCache[url] {
            return cached
        }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let uiImage = UIImage(data: data),
                  let ciImage = CIImage(image: uiImage) else {
                return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
            }

            let extent = ciImage.extent
            let topRect = CGRect(x: 0, y: extent.height * 0.45, width: extent.width, height: extent.height * 0.55)
            let bottomRect = CGRect(x: 0, y: 0, width: extent.width, height: extent.height * 0.50)

            let topUIColor = sampleAverageColor(from: ciImage, in: topRect)
            let bottomUIColor = sampleAverageColor(from: ciImage, in: bottomRect)

            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            topUIColor.getRed(&r, green: &g, blue: &b, alpha: &a)
            let darkAccentUI = UIColor(red: r * 0.35, green: g * 0.35, blue: b * 0.35, alpha: 1.0)

            let result = (
                primary: Color(topUIColor),
                secondary: Color(bottomUIColor),
                darkAccent: Color(darkAccentUI)
            )

            self.colorCache[url] = result
            return result
        } catch {
            return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
        }
    }

    private func sampleAverageColor(from ciImage: CIImage, in rect: CGRect) -> UIColor {
        let filter = CIFilter(name: "CIAreaAverage", parameters: [
            kCIInputImageKey: ciImage,
            kCIInputExtentKey: CIVector(cgRect: rect)
        ])
        guard let output = filter?.outputImage else {
            return UIColor(red: 0.25, green: 0.15, blue: 0.10, alpha: 1.0)
        }

        var bitmap = [UInt8](repeating: 0, count: 4)
        context.render(
            output,
            toBitmap: &bitmap,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: nil
        )

        let red = max(CGFloat(bitmap[0]) / 255.0, 0.14)
        let green = max(CGFloat(bitmap[1]) / 255.0, 0.10)
        let blue = max(CGFloat(bitmap[2]) / 255.0, 0.08)

        return UIColor(red: red, green: green, blue: blue, alpha: 1.0)
    }
}
