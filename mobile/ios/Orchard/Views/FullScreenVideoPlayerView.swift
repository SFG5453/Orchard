import SwiftUI
import AVKit

// MARK: - Full Screen Video Player

struct FullScreenVideoPlayerView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @Binding var isPresented: Bool
    @State private var areControlsVisible: Bool = true
    @State private var hideControlsTask: Task<Void, Never>?
    @State private var isScrubbing: Bool = false
    @State private var scrubTime: Double = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let av = player.avPlayer {
                VideoPlayer(player: av)
                    .ignoresSafeArea()
            }

            // Tap gesture background to toggle controls
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        areControlsVisible.toggle()
                    }
                    if areControlsVisible {
                        scheduleHideControls()
                    }
                }

            // Overlay Controls
            if areControlsVisible {
                VStack {
                    // Top Bar
                    HStack(alignment: .center) {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isPresented = false
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.black.opacity(0.65)))
                        }

                        if let track = player.currentTrack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.title)
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(track.artist)
                                    .font(.system(size: 13))
                                    .foregroundColor(.white.opacity(0.7))
                                    .lineLimit(1)
                            }
                            .padding(.leading, 8)
                        }

                        Spacer()

                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isPresented = false
                        } label: {
                            Image(systemName: "arrow.down.right.and.arrow.up.left")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.black.opacity(0.65)))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 16)

                    Spacer()

                    // Center Transport Controls
                    HStack(spacing: 50) {
                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            let newTime = max(player.currentTime - 10, 0)
                            player.seek(to: newTime)
                            scheduleHideControls()
                        } label: {
                            Image(systemName: "gobackward.10")
                                .font(.system(size: 32))
                                .foregroundColor(.white)
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                            player.togglePlayPause()
                            scheduleHideControls()
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(Color.white.opacity(0.9))
                                    .frame(width: 72, height: 72)
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 32, weight: .bold))
                                    .foregroundColor(.black)
                                    .offset(x: player.isPlaying ? 0 : 2)
                            }
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            let newTime = min(player.currentTime + 10, player.duration)
                            player.seek(to: newTime)
                            scheduleHideControls()
                        } label: {
                            Image(systemName: "goforward.10")
                                .font(.system(size: 32))
                                .foregroundColor(.white)
                        }
                    }

                    Spacer()

                    // Bottom Scrubber Bar
                    VStack(spacing: 6) {
                        let activeDuration = max(player.duration, 1)
                        let activeTime = isScrubbing ? scrubTime : player.currentTime

                        Slider(
                            value: Binding(
                                get: { activeTime },
                                set: { newValue in
                                    scrubTime = newValue
                                }
                            ),
                            in: 0...activeDuration,
                            onEditingChanged: { editing in
                                isScrubbing = editing
                                if !editing {
                                    player.seek(to: scrubTime)
                                    scheduleHideControls()
                                }
                            }
                        )
                        .tint(.purple)

                        HStack {
                            Text(formatTime(activeTime))
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                            Spacer()
                            Text(formatTime(activeDuration))
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 36)
                }
                .background(
                    LinearGradient(
                        colors: [Color.black.opacity(0.7), Color.clear, Color.black.opacity(0.85)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .ignoresSafeArea()
                )
                .transition(.opacity)
            }
        }
        .onAppear {
            scheduleHideControls()
        }
    }

    private func scheduleHideControls() {
        hideControlsTask?.cancel()
        hideControlsTask = Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeInOut(duration: 0.3)) {
                    areControlsVisible = false
                }
            }
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite && seconds >= 0 else { return "0:00" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }
}
