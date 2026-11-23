import SwiftUI

public struct PlaylistImportSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var playlistService = PlaylistService.shared

    @State private var selectedTab: Int = 0
    @State private var inputUrl: String = ""
    @State private var isLoading: Bool = false
    @State private var errorMessage: String? = nil
    @State private var successMessage: String? = nil

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 24) {
                    // Segmented Platform Selector: Spotify vs Apple Music
                    Picker("Platform", selection: $selectedTab) {
                        Text("Spotify").tag(0)
                        Text("Apple Music").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 24)
                    .padding(.top, 16)

                    if selectedTab == 0 {
                        spotifyImportSection
                    } else {
                        appleMusicImportSection
                    }

                    // Status Messages
                    if let error = errorMessage {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.circle.fill")
                                .foregroundColor(.red)
                            Text(error)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.red)
                        }
                        .padding(.horizontal, 24)
                    }

                    if let success = successMessage {
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text(success)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(.green)
                        }
                        .padding(.horizontal, 24)
                    }

                    Spacer()
                }
            }
            .navigationTitle("Import Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        dismiss()
                    }
                    .foregroundColor(.white.opacity(0.8))
                }
            }
        }
    }

    // MARK: - Spotify Section

    private var spotifyImportSection: some View {
        VStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "music.note")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.green)

                    Text("Spotify Playlist Link")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                }

                Text("Paste any public Spotify playlist URL or share link to import its complete tracklist into Orchard.")
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.6))
                    .lineSpacing(3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)

            // URL Text Field
            HStack {
                Image(systemName: "link")
                    .foregroundColor(.white.opacity(0.4))

                TextField("https://open.spotify.com/playlist/...", text: $inputUrl)
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                if !inputUrl.isEmpty {
                    Button {
                        inputUrl = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.white.opacity(0.4))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.08)))
            .padding(.horizontal, 24)

            // Import Button
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                performSpotifyImport()
            } label: {
                HStack(spacing: 8) {
                    if isLoading {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .black))
                    } else {
                        Image(systemName: "arrow.down.circle.fill")
                        Text("Import Spotify Playlist")
                            .font(.system(size: 15, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Color.green)
                .foregroundColor(.black)
                .cornerRadius(14)
            }
            .disabled(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isLoading)
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Apple Music Section

    private var appleMusicImportSection: some View {
        VStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "apple.logo")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.pink)

                    Text("Apple Music Import")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                }

                Text("Import directly from your iPhone Music library or paste an Apple Music playlist link.")
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.6))
                    .lineSpacing(3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)

            // Direct On-Device Library Import Button
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                performDeviceAppleMusicImport()
            } label: {
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.pink.opacity(0.2))
                            .frame(width: 38, height: 38)
                        Image(systemName: "iphone.and.arrow.forward")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.pink)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Import from iPhone Library")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)
                        Text("One-tap sync for your local playlists")
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.5))
                    }

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.white.opacity(0.3))
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color.white.opacity(0.08)))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)

            // Divider or Label
            HStack {
                Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1)
                Text("OR PASTE LINK")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white.opacity(0.4))
                Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1)
            }
            .padding(.horizontal, 24)

            // URL Text Field
            HStack {
                Image(systemName: "link")
                    .foregroundColor(.white.opacity(0.4))

                TextField("https://music.apple.com/.../playlist/...", text: $inputUrl)
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                if !inputUrl.isEmpty {
                    Button {
                        inputUrl = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.white.opacity(0.4))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.08)))
            .padding(.horizontal, 24)

            // Import Button
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                performAppleMusicLinkImport()
            } label: {
                HStack(spacing: 8) {
                    if isLoading {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    } else {
                        Image(systemName: "arrow.down.circle.fill")
                        Text("Import Apple Music Link")
                            .font(.system(size: 15, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Color.pink)
                .foregroundColor(.white)
                .cornerRadius(14)
            }
            .disabled(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isLoading)
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Actions

    private func performSpotifyImport() {
        isLoading = true
        errorMessage = nil
        successMessage = nil

        Task {
            do {
                let playlist = try await playlistService.importFromSpotify(url: inputUrl)
                await MainActor.run {
                    self.isLoading = false
                    self.successMessage = "Successfully imported \"\(playlist.title)\" with \(playlist.tracks.count) tracks!"
                    self.inputUrl = ""
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        self.dismiss()
                    }
                }
            } catch {
                await MainActor.run {
                    self.isLoading = false
                    self.errorMessage = "Failed to import Spotify playlist. Please check the URL."
                }
            }
        }
    }

    private func performAppleMusicLinkImport() {
        isLoading = true
        errorMessage = nil
        successMessage = nil

        Task {
            do {
                let playlist = try await playlistService.importFromAppleMusicLink(url: inputUrl)
                await MainActor.run {
                    self.isLoading = false
                    self.successMessage = "Successfully imported \"\(playlist.title)\" with \(playlist.tracks.count) tracks!"
                    self.inputUrl = ""
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        self.dismiss()
                    }
                }
            } catch {
                await MainActor.run {
                    self.isLoading = false
                    self.errorMessage = "Failed to import Apple Music playlist. Please check the URL."
                }
            }
        }
    }

    private func performDeviceAppleMusicImport() {
        isLoading = true
        errorMessage = nil
        successMessage = nil

        Task {
            do {
                let imported = try await playlistService.importFromDeviceAppleMusic()
                await MainActor.run {
                    self.isLoading = false
                    if imported.isEmpty {
                        self.errorMessage = "No playlists found in your Apple Music library."
                    } else {
                        self.successMessage = "Imported \(imported.count) playlists from Apple Music!"
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                            self.dismiss()
                        }
                    }
                }
            } catch {
                await MainActor.run {
                    self.isLoading = false
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }
}
