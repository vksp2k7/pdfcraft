# Android WebView input and selection: findings and implementation

## Findings

### Soft keyboard and egui text fields

PdfCraft’s Android APK hosts the existing eframe/WebAssembly UI inside Android WebView; it does not use native Android `EditText` widgets. The WebRunner therefore owns web-side IME handling through its hidden text agent. Egui 0.36.0 explicitly introduced improved mobile keyboard support for eframe/web, and PdfCraft resolves eframe/egui 0.36.2. A second, competing keyboard implementation should not replace that text agent or force-hide the IME during transient focus changes.

Android requires the editor view and window to be focused before the input method is connected, and notes that a show request can be ignored before that connection exists. Eframe’s WebRunner programmatically focuses one hidden HTML input at startup and keeps using that same input for later egui text widgets. Therefore, a DOM `focusin` event is not a reliable indication that the user just entered a text field; it can happen during startup, before the Android bridge is installed, and may not fire again as egui changes focused widgets. The app now provides a persistent floating Keyboard button. Tapping it refocuses eframe’s hidden input and makes an explicit native request using `WindowInsetsControllerCompat` plus `InputMethodManager`, posted/retried until the WebView window is focused. This avoids opening the keyboard automatically on app startup and gives users a direct fallback when WebView does not request the IME on a field tap.

The input bridge is registered with AndroidX WebKit’s document-start script API when supported, before eframe initializes, with the existing page-finished injection retained as an idempotent fallback.

Android documents that `addJavascriptInterface` exposes methods to all frames. The host therefore serves only the bundled `appassets` path inside WebView and blocks external frames/subresources; top-level external links continue to open outside the app. This keeps clipboard reads and file-export methods confined to the trusted packaged UI.

### Touch selection and context menus

The PDF page and PdfCraft’s form widgets are drawn on a canvas, not represented as selectable HTML text. Consequently, Android WebView’s built-in text-selection handles/context menu cannot select PDF glyphs. Selection and Copy must be implemented against PdfCraft’s extracted PDF text layer and rendered egui selection actions; translating a WebView long-click directly into an Android native text-selection menu is the wrong abstraction.

Egui 0.36.2 already recognizes a stationary touch hold (default `max_click_duration` is 0.8 seconds) and exposes it as `Response::long_touched()` / a secondary-click response, which is intended to open context menus. PdfCraft’s canvas hit-testing instead used `PointerState::hover_pos()`. Egui documents `hover_pos()` as the latest mouse position and says it is `None` for touchscreen taps; `interact_pos()` is the API that returns the touch location. This meant the page interaction existed but the text-glyph hit test had no touch coordinate. The fix uses `interact_pos()` and, on a long press over a glyph in Select mode, selects that word in PdfCraft’s own model before its existing context menu is populated. That menu can then offer the selected-text Copy action.

### Clipboard and paste

The Web Clipboard API is permission/user-activation constrained. The W3C Clipboard specification also states that a synthetic `paste` event does not itself read the real system clipboard or perform the browser’s default paste. Therefore, a synthetic event is only useful after a trusted native operation has explicitly supplied clipboard text. PdfCraft’s Android bridge reads/writes Android’s `ClipboardManager`, routes eframe clipboard calls through a direct Clipboard API shim, and dispatches an egui paste event containing the text it just read from Android.

Because the canvas text agent is an IME composition buffer rather than a normal HTML field containing the complete egui text value, the host now exposes a visible Paste button while that agent is focused. The button preserves the egui focus and sends clipboard text through eframe’s paste event path. PDF copy continues to use PdfCraft’s own selected-text Copy action.

## Sources

- Android, [Handle input method visibility](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility): focus prerequisites, reliable IME display, resize behavior, and guidance not to force-hide the keyboard.
- AndroidX Core, [WindowInsetsControllerCompat](https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat): compatibility API for requesting IME visibility.
- Android, [WebView reference](https://developer.android.com/reference/android/webkit/WebView): WebView text-editor/input-connection APIs.
- Android, [WebView.addJavascriptInterface](https://developer.android.com/reference/android/webkit/WebView#addJavascriptInterface(java.lang.Object,java.lang.String)): JavaScript interface exposure across frames and origin-related security considerations.
- AndroidX, [WebViewCompat.addDocumentStartJavaScript](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)): origin-scoped injection before page scripts, with feature detection required.
- egui, [0.36.0 release notes](https://github.com/emilk/egui/releases/tag/0.36.0): improved mobile keyboard support in eframe/web.
- egui, [mobile IME implementation PR #8045](https://github.com/emilk/egui/pull/8045): robustness changes and Android IME testing.
- egui 0.36.2 API docs: [`PointerState`](https://docs.rs/egui/0.36.2/egui/struct.PointerState.html), [`Response`](https://docs.rs/egui/0.36.2/egui/struct.Response.html), [`InputOptions`](https://docs.rs/egui/0.36.2/egui/struct.InputOptions.html), and [`Event`](https://docs.rs/egui/0.36.2/egui/enum.Event.html).
- W3C, [Clipboard API](https://www.w3.org/TR/clipboard-apis/): clipboard permission/user-intent constraints and synthetic paste-event semantics.

## Verification notes

For the floating keyboard follow-up, Android Kotlin/Gradle compilation succeeded and a DOM-mock check confirmed the button mounts, refocuses the hidden eframe input, and invokes the native IME request. The GitHub Actions WASM-to-APK build should be used to validate packaging; actual soft-keyboard behavior still requires an Android device, which is not attached to the sandbox.

### Manual device checklist

1. Open a PDF, long-press a printed word in Select mode, and confirm the word highlights and PdfCraft’s context menu exposes Copy.
2. Tap a PdfCraft text field, then tap the floating Keyboard button if the soft keyboard did not appear. Confirm it opens; dismiss it with Back and tap Keyboard again to reopen it.
3. Copy text in another app, focus a PdfCraft text field, tap the visible Paste control, and confirm the text is inserted.
4. Select PDF text with a drag or long press, choose Copy, then paste into another app to confirm the Android system clipboard was updated.
