import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Keeps the screen on only where the user chose it, and dims a screen that the app keeps on after a minute without touch.
/// A touch makes it bright again. Leaving the screen or the app restores the system behaviour and brightness.
@MainActor
@Observable
final class ScreenAwake {
    @ObservationIgnored private var dimTask: Task<Void, Never>?
    @ObservationIgnored private var savedBrightness: CGFloat?
    @ObservationIgnored private var wanted = false
    @ObservationIgnored private var dim = false
    /// A tenth of the full brightness: dark enough to save power, bright enough to see that the app is on.
    static let dimBrightness: CGFloat = 0.1
    static let dimDelay: Duration = .seconds(60)

    func update(keepOn: Bool, dim: Bool, active: Bool) {
        wanted = keepOn && active
        self.dim = dim && wanted
        UIApplication.shared.isIdleTimerDisabled = wanted
        touched()
    }

    /// A touch or a press: bright again, and a new wait.
    func touched() {
        dimTask?.cancel()
        restore()
        guard dim else { return }
        dimTask = Task { [weak self] in
            try? await Task.sleep(for: Self.dimDelay)
            guard !Task.isCancelled, let self, let screen = Self.screen else { return }
            self.savedBrightness = screen.brightness
            screen.brightness = Self.dimBrightness
        }
    }

    private func restore() {
        if let saved = savedBrightness, let screen = Self.screen { screen.brightness = saved }
        savedBrightness = nil
    }

    private static var screen: UIScreen? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.screen }.first
    }
}

/// Reports each touch on the window without taking it from the views.
struct TouchObserver: UIViewRepresentable {
    var onTouch: () -> Void

    func makeUIView(context: Context) -> TouchView {
        let view = TouchView()
        view.onTouch = onTouch
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: TouchView, context: Context) {
        view.onTouch = onTouch
    }

    final class TouchView: UIView {
        var onTouch: () -> Void = {}
        private var recognizer: Recognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, recognizer == nil else { return }
            let recognizer = Recognizer(target: nil, action: nil)
            recognizer.onTouch = { [weak self] in self?.onTouch() }
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            window.addGestureRecognizer(recognizer)
            self.recognizer = recognizer
        }
    }

    /// Sees each touch, then fails, so the touch goes on to the views.
    final class Recognizer: UIGestureRecognizer {
        var onTouch: () -> Void = {}

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            onTouch()
            state = .failed
        }
    }
}
