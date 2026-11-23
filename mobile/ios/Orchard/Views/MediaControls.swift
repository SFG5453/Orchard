import SwiftUI
import MediaPlayer
import AVKit

// MARK: - UIKit System Volume Slider

final class SleekMPVolumeView: MPVolumeView {
    override func layoutSubviews() {
        super.layoutSubviews()
        showsRouteButton = false
        for view in subviews {
            if let slider = view as? UISlider {
                slider.tintColor = .white
                slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.20)
                let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
                let thumb = renderer.image { ctx in
                    ctx.cgContext.setFillColor(UIColor.white.cgColor)
                    ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 8, height: 8))
                }
                slider.setThumbImage(thumb, for: .normal)
                slider.setThumbImage(thumb, for: .highlighted)
            }
        }
    }
}

struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> SleekMPVolumeView {
        let volumeView = SleekMPVolumeView(frame: .zero)
        volumeView.showsRouteButton = false
        volumeView.tintColor = .white
        return volumeView
    }

    func updateUIView(_ uiView: SleekMPVolumeView, context: Context) {}
}

// MARK: - Native AirPlay Audio Route Picker

struct AirPlayRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView(frame: .zero)
        picker.tintColor = UIColor.white.withAlphaComponent(0.75)
        picker.activeTintColor = UIColor.systemPurple
        picker.prioritizesVideoDevices = false
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
