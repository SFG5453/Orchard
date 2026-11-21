import SwiftUI

public struct LyricsView: View {
    @ObservedObject var player = AudioPlayerService.shared

    public init() {}

    public var body: some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 24) {
                    if player.currentLyrics.isEmpty {
                        VStack(spacing: 12) {
                            Spacer(minLength: 120)
                            Image(systemName: "quote.bubble")
                                .font(.system(size: 44))
                                .foregroundColor(.white.opacity(0.4))
                            Text("No lyrics available")
                                .font(.headline)
                                .foregroundColor(.white.opacity(0.6))
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        ForEach(Array(player.currentLyrics.enumerated()), id: \.element.id) { index, line in
                            let isActive = player.currentLyricIndex == index
                            Text(line.text)
                                .font(.system(size: isActive ? 26 : 22, weight: isActive ? .bold : .medium, design: .rounded))
                                .foregroundColor(isActive ? .white : .white.opacity(0.35))
                                .scaleEffect(isActive ? 1.03 : 1.0, anchor: .leading)
                                .animation(.spring(response: 0.35, dampingFraction: 0.7), value: isActive)
                                .id(index)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    player.seek(to: line.startTime)
                                }
                                .padding(.horizontal, 24)
                        }
                    }
                }
                .padding(.vertical, 80)
            }
            .onChange(of: player.currentLyricIndex) { newIndex in
                if let index = newIndex {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        proxy.scrollTo(index, anchor: .center)
                    }
                }
            }
        }
    }
}
