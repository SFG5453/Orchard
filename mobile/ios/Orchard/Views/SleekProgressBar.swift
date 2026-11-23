import SwiftUI

// MARK: - Sleek Apple Music Scrubber

struct SleekProgressBar: View {
    let currentTime: Double
    let duration: Double
    @Binding var isScrubbing: Bool
    @Binding var scrubTime: Double
    let onSeek: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let total = max(duration, 1.0)
            let active = isScrubbing ? scrubTime : currentTime
            let progress = min(max(active / total, 0.0), 1.0)
            let width = geo.size.width

            ZStack(alignment: .leading) {
                // Background Track
                Capsule()
                    .fill(Color.white.opacity(0.18))
                    .frame(height: isScrubbing ? 6 : 4)

                // Elapsed Track
                Capsule()
                    .fill(Color.white)
                    .frame(width: max(width * progress, 0), height: isScrubbing ? 6 : 4)
            }
            .contentShape(Rectangle().inset(by: -14))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        isScrubbing = true
                        let pct = min(max(value.location.x / width, 0.0), 1.0)
                        scrubTime = pct * total
                    }
                    .onEnded { value in
                        let pct = min(max(value.location.x / width, 0.0), 1.0)
                        let target = pct * total
                        isScrubbing = false
                        onSeek(target)
                    }
            )
            .animation(.spring(response: 0.2, dampingFraction: 0.8), value: isScrubbing)
        }
        .frame(height: 14)
    }
}
