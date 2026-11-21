import Foundation

public struct LyricLine: Identifiable, Equatable {
    public let id = UUID()
    public let startTime: TimeInterval
    public let text: String

    public init(startTime: TimeInterval, text: String) {
        self.startTime = startTime
        self.text = text
    }

    public static func parseLRC(_ lrcContent: String) -> [LyricLine] {
        var lines: [LyricLine] = []
        let pattern = #"\[(\d{2}):(\d{2})\.?(\d{2,3})?\](.*)"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }

        let nsString = lrcContent as NSString
        let matches = regex.matches(in: lrcContent, range: NSRange(location: 0, length: nsString.length))

        for match in matches {
            guard match.numberOfRanges >= 5 else { continue }
            let minutesStr = nsString.substring(with: match.range(at: 1))
            let secondsStr = nsString.substring(with: match.range(at: 2))
            let millisStr = match.range(at: 3).location != NSNotFound ? nsString.substring(with: match.range(at: 3)) : "0"
            let text = nsString.substring(with: match.range(at: 4)).trimmingCharacters(in: .whitespacesAndNewlines)

            let minutes = Double(minutesStr) ?? 0.0
            let seconds = Double(secondsStr) ?? 0.0
            let millis = (Double(millisStr) ?? 0.0) / (millisStr.count == 3 ? 1000.0 : 100.0)

            let totalSeconds = (minutes * 60.0) + seconds + millis
            if !text.isEmpty {
                lines.append(LyricLine(startTime: totalSeconds, text: text))
            }
        }

        return lines.sorted { $0.startTime < $1.startTime }
    }
}
