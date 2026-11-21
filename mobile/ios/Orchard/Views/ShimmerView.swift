import SwiftUI

public struct ShimmerModifier: ViewModifier {
    @State private var phase: CGFloat = -1

    public func body(content: Content) -> some View {
        content
            .overlay(
                GeometryReader { geo in
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.0),
                            Color.white.opacity(0.18),
                            Color.white.opacity(0.0)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .frame(width: geo.size.width * 1.5)
                    .offset(x: geo.size.width * phase)
                }
            )
            .mask(content)
            .onAppear {
                withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) {
                    phase = 1.5
                }
            }
    }
}

public extension View {
    func shimmering() -> some View {
        modifier(ShimmerModifier())
    }
}

public struct HomeSkeletonView: View {
    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 32) {
            // Header skeleton
            HStack(spacing: 12) {
                Circle()
                    .fill(Color(white: 0.16))
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Color(white: 0.16))
                        .frame(width: 130, height: 24)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color(white: 0.12))
                        .frame(width: 180, height: 14)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .shimmering()

            // Shelf 1 skeleton
            shelfSkeleton
            // Shelf 2 skeleton
            shelfSkeleton
        }
    }

    private var shelfSkeleton: some View {
        VStack(alignment: .leading, spacing: 14) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(white: 0.16))
                .frame(width: 140, height: 20)
                .padding(.horizontal, 20)
                .shimmering()

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(0..<4, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(Color(white: 0.14))
                                .frame(width: 148, height: 148)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color(white: 0.14))
                                .frame(width: 130, height: 14)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color(white: 0.10))
                                .frame(width: 90, height: 12)
                        }
                        .shimmering()
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }
}
