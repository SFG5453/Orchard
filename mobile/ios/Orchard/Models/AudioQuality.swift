import Foundation

public enum AudioQuality: String, CaseIterable, Identifiable, Codable {
    case low = "Data Saver"
    case medium = "Normal"
    case high = "High Quality"
    case lossless = "Hi-Fi Opus"

    public var id: String { rawValue }

    public var badge: String {
        switch self {
        case .low: return "64 KBPS"
        case .medium: return "128 KBPS"
        case .high: return "256K AAC"
        case .lossless: return "HI-FI OPUS"
        }
    }

    public var description: String {
        switch self {
        case .low: return "Minimal cellular data usage (~30 MB/hr)"
        case .medium: return "Standard audio quality (~60 MB/hr)"
        case .high: return "High fidelity studio audio (~115 MB/hr)"
        case .lossless: return "Lossless acoustic clarity, native WebM/Opus"
        }
    }

    public var itags: [Int] {
        switch self {
        case .lossless: return [251, 140, 250, 139]
        case .high: return [140, 251, 250, 139]
        case .medium: return [250, 140, 139, 251]
        case .low: return [139, 249, 250, 140]
        }
    }
}
