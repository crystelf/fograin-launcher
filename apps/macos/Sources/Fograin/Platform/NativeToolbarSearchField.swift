import AppKit
import SwiftUI

/// A real `NSSearchField` for the native toolbar. Gives the user the standard
/// macOS search field, cancel button and IME behavior instead of a custom pill.
struct NativeSearchField: NSViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var focusRequest: Int = 0
    var onSubmit: (() -> Void)?
    var onFocusHandled: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> NSSearchField {
        let field = NSSearchField()
        field.delegate = context.coordinator
        field.target = context.coordinator
        field.action = #selector(Coordinator.submit(_:))
        field.sendsSearchStringImmediately = false
        field.sendsWholeSearchString = false
        field.placeholderString = placeholder
        field.controlSize = .regular
        field.setAccessibilityLabel(placeholder)
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        context.coordinator.field = field
        context.coordinator.lastFocusRequest = focusRequest


        return field
    }

    func updateNSView(_ nsView: NSSearchField, context: Context) {
        context.coordinator.parent = self
        if nsView.stringValue != text {
            nsView.stringValue = text
        }
        if nsView.placeholderString != placeholder {
            nsView.placeholderString = placeholder
        }
        if focusRequest > context.coordinator.lastFocusRequest {
            context.coordinator.lastFocusRequest = focusRequest
            DispatchQueue.main.async { [weak nsView] in
                guard let nsView, let window = nsView.window else { return }
                window.makeFirstResponder(nsView)
                nsView.currentEditor()?.selectAll(nil)
                onFocusHandled?()
            }
        } else if focusRequest < context.coordinator.lastFocusRequest {
            context.coordinator.lastFocusRequest = focusRequest
        }
    }

    final class Coordinator: NSObject, NSSearchFieldDelegate {
        var parent: NativeSearchField
        weak var field: NSSearchField?
        var lastFocusRequest: Int

        init(_ parent: NativeSearchField) {
            self.parent = parent
            self.lastFocusRequest = parent.focusRequest
        }

        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSSearchField else { return }
            parent.text = field.stringValue
        }

        @objc func submit(_ sender: NSSearchField) {
            parent.onSubmit?()
        }

        func control(
            _ control: NSControl,
            textView: NSTextView,
            doCommandBy commandSelector: Selector
        ) -> Bool {
            if commandSelector == #selector(NSResponder.insertNewline(_:)) {
                parent.onSubmit?()
                return true
            }
            if commandSelector == #selector(NSResponder.cancelOperation(_:)) {
                parent.text = ""
                control.window?.makeFirstResponder(nil)
                return true
            }
            return false
        }
    }
}
