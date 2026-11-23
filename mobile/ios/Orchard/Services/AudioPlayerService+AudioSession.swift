import Foundation
import AVFoundation
import UIKit

extension AudioPlayerService {
    func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true, options: [])
        } catch {
            print("[AudioPlayerService] AudioSession setup error: \(error.localizedDescription)")
        }
        setupAudioSessionObservers()
    }

    private func setupAudioSessionObservers() {
        // Handle audio interruptions (phone calls, alarms, Siri, other apps)
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

            switch type {
            case .began:
                self.pauseCurrent()
            case .ended:
                guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    try? AVAudioSession.sharedInstance().setActive(true)
                    self.playCurrent()
                }
            @unknown default:
                break
            }
        }

        // Handle route changes (Bluetooth / headphones disconnection)
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

            if reason == .oldDeviceUnavailable {
                // Audio output was unplugged or Bluetooth disconnected — pause to prevent blasting speaker
                self.pauseCurrent()
            }
        }

        // Handle system media server recovery
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("[AudioPlayerService] Media server reset by iOS — restoring audio engine")
            self?.setupAudioSession()
            if let track = self?.currentTrack {
                self?.loadAndPlay(track: track, seekTo: self?.currentTime ?? 0)
            }
        }
    }
}
