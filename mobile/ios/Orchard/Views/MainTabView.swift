import SwiftUI

extension Notification.Name {
    public static let focusSearchField = Notification.Name("focusSearchField")
}

public struct MainTabView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @State private var isPlayerPresented: Bool = false
    @State private var selectedTab: Int = 0
    @State private var lastSearchTapTime: TimeInterval = 0

    public init() {}

    private var tabSelectionBinding: Binding<Int> {
        Binding(
            get: { selectedTab },
            set: { newTab in
                if newTab == 1 {
                    let now = Date().timeIntervalSince1970
                    if selectedTab == 1 {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        NotificationCenter.default.post(name: .focusSearchField, object: nil)
                    } else if (now - lastSearchTapTime) < 0.5 {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        NotificationCenter.default.post(name: .focusSearchField, object: nil)
                        lastSearchTapTime = 0
                        selectedTab = newTab
                        return
                    }
                    lastSearchTapTime = now
                }
                selectedTab = newTab
            }
        )
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: tabSelectionBinding) {
                HomeView()
                    .tabItem {
                        Label("Home", systemImage: "house.fill")
                    }
                    .tag(0)

                SearchView()
                    .tabItem {
                        Label("Search", systemImage: "magnifyingglass")
                    }
                    .tag(1)

                LibraryView()
                    .tabItem {
                        Label("Library", systemImage: "heart.fill")
                    }
                    .tag(2)

                QueueView()
                    .tabItem {
                        Label("Queue", systemImage: "music.note.list")
                    }
                    .tag(3)
            }
            .tint(.purple)
            .background(
                TabBarTapDetector {
                    NotificationCenter.default.post(name: .focusSearchField, object: nil)
                }
            )

            // Mini Player Floating Bar
            if player.currentTrack != nil {
                MiniPlayerView(isPlayerPresented: $isPlayerPresented)
                    .padding(.bottom, 86)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .animation(.spring(response: 0.4, dampingFraction: 0.8), value: player.currentTrack)
            }
        }
        .fullScreenCover(isPresented: $isPlayerPresented) {
            PlayerView(isPresented: $isPlayerPresented)
        }
    }
}

// MARK: - UIKit Tab Bar Tap Detector for Double-Tap Search Gesture

struct TabBarTapDetector: UIViewControllerRepresentable {
    let onSearchDoubleTapped: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        let vc = UIViewController()
        DispatchQueue.main.async {
            if let tabController = vc.tabBarController {
                context.coordinator.setup(tabBarController: tabController, onSearchDoubleTapped: onSearchDoubleTapped)
            }
        }
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        DispatchQueue.main.async {
            if let tabController = uiViewController.tabBarController {
                context.coordinator.setup(tabBarController: tabController, onSearchDoubleTapped: onSearchDoubleTapped)
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    class Coordinator: NSObject, UITabBarControllerDelegate {
        weak var tabBarController: UITabBarController?
        var onSearchDoubleTapped: (() -> Void)?
        var lastSearchTapTime: TimeInterval = 0

        func setup(tabBarController: UITabBarController, onSearchDoubleTapped: @escaping () -> Void) {
            self.tabBarController = tabBarController
            self.onSearchDoubleTapped = onSearchDoubleTapped
            tabBarController.delegate = self
        }

        func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) {
            if let index = tabBarController.viewControllers?.firstIndex(of: viewController), index == 1 {
                let now = Date().timeIntervalSince1970
                if (now - lastSearchTapTime) < 0.55 {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onSearchDoubleTapped?()
                    lastSearchTapTime = 0
                } else {
                    lastSearchTapTime = now
                }
            }
        }
    }
}
