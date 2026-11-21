import SwiftUI

@main
struct OrchardApp: App {
    init() {
        // Initialize background audio session on launch
        _ = AudioPlayerService.shared
    }

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .preferredColorScheme(.dark)
        }
    }
}
