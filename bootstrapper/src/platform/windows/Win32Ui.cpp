/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/windows/Wide.hpp"
#include "ui/ProgressUi.hpp"

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace orchard::boot {

namespace {

using platform::widen;

// Orchard's dark palette (app/qml theme).
constexpr COLORREF kBackground = RGB(0x0d, 0x0f, 0x12);
constexpr COLORREF kText = RGB(0xf0, 0xee, 0xe7);
constexpr COLORREF kMuted = RGB(0x92, 0x99, 0xa3);
constexpr COLORREF kTrack = RGB(0x1f, 0x23, 0x29);
constexpr COLORREF kAccent = RGB(0x7f, 0xbe, 0x90);
constexpr COLORREF kButton = RGB(0x2a, 0x2f, 0x36);
constexpr UINT kCloseMessage = WM_APP + 1;
constexpr int kWidth = 440;
constexpr int kHeight = 220;

// One custom-painted window: title, headline, status, bar, Cancel. No
// controls library, no wizard pages.
class Win32Ui final : public ProgressUi {
public:
  ~Win32Ui() override { close(); }

  void setHeadline(const std::string &text) override { update([&] { headline_ = widen(text); }); }
  void setStatus(const std::string &text) override { update([&] { status_ = widen(text); }); }
  void setProgress(std::uint64_t done, std::uint64_t total) override {
    update([&] {
      done_ = done;
      total_ = total;
    });
  }
  bool cancelRequested() override { return cancel_; }
  bool confirm(const std::string &question) override {
    return MessageBoxW(hwnd_, widen(question).c_str(), L"Orchard", MB_YESNO | MB_ICONQUESTION) == IDYES;
  }
  void showError(const std::string &message) override {
    close();
    MessageBoxW(nullptr, widen(message).c_str(), L"Orchard", MB_OK | MB_ICONERROR);
  }
  void close() override {
    if (thread_.joinable()) {
      PostMessageW(hwnd_, kCloseMessage, 0, 0);
      thread_.join();
    }
    hwnd_ = nullptr;
  }

private:
  template <typename F> void update(F change) {
    {
      std::lock_guard lock(mutex_);
      change();
    }
    if (HWND hwnd = window())
      InvalidateRect(hwnd, nullptr, FALSE);
  }

  // Created on first use so a confirm-only run never flashes a window.
  HWND window() {
    if (!thread_.joinable()) {
      thread_ = std::thread([this] { run(); });
      std::unique_lock lock(mutex_);
      ready_.wait(lock, [this] { return started_; });
    }
    return hwnd_;
  }

  void run() {
    HINSTANCE instance = GetModuleHandleW(nullptr);
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof wc;
    wc.lpfnWndProc = &Win32Ui::proc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
    wc.lpszClassName = L"OrchardBootstrapper";
    RegisterClassExW(&wc);
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"Orchard", style, CW_USEDEFAULT, CW_USEDEFAULT, kWidth,
                                kHeight, nullptr, nullptr, instance, this);
    if (hwnd) {
      darkTitleBar(hwnd);
      scale_ = dpiOf(hwnd) / 96.0;
      RECT frame{0, 0, px(kWidth), px(kHeight)};
      AdjustWindowRectEx(&frame, style, FALSE, 0);
      const int w = frame.right - frame.left;
      const int h = frame.bottom - frame.top;
      SetWindowPos(hwnd, nullptr, (GetSystemMetrics(SM_CXSCREEN) - w) / 2, (GetSystemMetrics(SM_CYSCREEN) - h) / 2,
                   w, h, SWP_NOZORDER);
      ShowWindow(hwnd, SW_SHOW);
      SetTimer(hwnd, 1, 33, nullptr);
    }
    {
      std::lock_guard lock(mutex_);
      hwnd_ = hwnd;
      started_ = true;
    }
    ready_.notify_all();
    MSG msg;
    while (hwnd && GetMessageW(&msg, nullptr, 0, 0) > 0) {
      TranslateMessage(&msg);
      DispatchMessageW(&msg);
    }
  }

  static UINT dpiOf(HWND hwnd) {
    using GetDpiForWindowFn = UINT(WINAPI *)(HWND);
    static const auto getDpi = reinterpret_cast<GetDpiForWindowFn>(
        reinterpret_cast<void *>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow")));
    if (getDpi)
      return getDpi(hwnd);
    HDC dc = GetDC(hwnd);
    const UINT dpi = static_cast<UINT>(GetDeviceCaps(dc, LOGPIXELSX));
    ReleaseDC(hwnd, dc);
    return dpi;
  }

  static void darkTitleBar(HWND hwnd) {
    using SetAttributeFn = HRESULT(WINAPI *)(HWND, DWORD, LPCVOID, DWORD);
    if (HMODULE dwm = LoadLibraryW(L"dwmapi.dll")) {
      if (auto set = reinterpret_cast<SetAttributeFn>(
              reinterpret_cast<void *>(GetProcAddress(dwm, "DwmSetWindowAttribute")))) {
        const BOOL dark = TRUE;
        set(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof dark);
      }
    }
  }

  int px(int value) const { return static_cast<int>(value * scale_ + 0.5); }

  RECT cancelRect() const {
    return RECT{px(kWidth - 28 - 96), px(kHeight - 24 - 32), px(kWidth - 28), px(kHeight - 24)};
  }

  HFONT font(int size, int weight) const {
    return CreateFontW(-px(size), 0, 0, 0, weight, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                       CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
  }

  void text(HDC dc, const std::wstring &value, RECT rect, HFONT font, COLORREF color, UINT align) const {
    SelectObject(dc, font);
    SetTextColor(dc, color);
    DrawTextW(dc, value.c_str(), -1, &rect, align | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX);
  }

  void fillRound(HDC dc, RECT rect, int radius, COLORREF color) const {
    HBRUSH brush = CreateSolidBrush(color);
    HPEN pen = CreatePen(PS_SOLID, 1, color);
    SelectObject(dc, brush);
    SelectObject(dc, pen);
    RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, radius, radius);
    DeleteObject(brush);
    DeleteObject(pen);
  }

  void paint(HDC target, const RECT &client) {
    std::wstring headline, status;
    std::uint64_t done, total;
    {
      std::lock_guard lock(mutex_);
      headline = headline_;
      status = cancel_ ? L"Cancelling" : status_;
      done = done_;
      total = total_;
    }
    HDC dc = CreateCompatibleDC(target);
    HBITMAP bitmap = CreateCompatibleBitmap(target, client.right, client.bottom);
    HGDIOBJ oldBitmap = SelectObject(dc, bitmap);
    HBRUSH background = CreateSolidBrush(kBackground);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT);

    HFONT title = font(26, FW_SEMIBOLD), body = font(14, FW_NORMAL), small = font(13, FW_NORMAL);
    const int left = px(28), right = client.right - px(28);
    text(dc, L"Orchard", {left, px(20), right, px(56)}, title, kText, DT_LEFT);
    text(dc, headline, {left, px(56), right, px(80)}, body, kMuted, DT_LEFT);
    text(dc, status, {left, px(100), right, px(122)}, body, kText, DT_LEFT);
    if (total > 0) {
      const std::wstring amount = widen(formatBytes(done) + " / " + formatBytes(total));
      text(dc, amount, {left, px(100), right, px(122)}, small, kMuted, DT_RIGHT);
    }
    const RECT track{left, px(130), right, px(136)};
    fillRound(dc, track, px(6), kTrack);
    if (total > 0) {
      const int width = static_cast<int>((track.right - track.left) * static_cast<double>(done) / double(total));
      if (width > 0)
        fillRound(dc, {track.left, track.top, track.left + std::max(width, px(6)), track.bottom}, px(6), kAccent);
      text(dc, std::to_wstring(100 * done / total) + L"%", {left, px(142), right, px(162)}, small, kMuted, DT_LEFT);
    } else {
      // Indeterminate: a short segment sliding along the track.
      const int span = (track.right - track.left) / 4;
      const int x = static_cast<int>((GetTickCount() / 4) % static_cast<DWORD>(track.right - track.left + span)) - span;
      fillRound(dc, {std::max(track.left, track.left + x), track.top, std::min(track.right, track.left + x + span),
                     track.bottom}, px(6), kAccent);
    }
    const RECT button = cancelRect();
    fillRound(dc, button, px(8), hover_ ? RGB(0x36, 0x3c, 0x45) : kButton);
    text(dc, L"Cancel", button, body, kText, DT_CENTER);

    BitBlt(target, 0, 0, client.right, client.bottom, dc, 0, 0, SRCCOPY);
    for (HFONT f : {title, body, small})
      DeleteObject(f);
    SelectObject(dc, oldBitmap);
    DeleteObject(bitmap);
    DeleteDC(dc);
  }

  static LRESULT CALLBACK proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_NCCREATE)
      SetWindowLongPtrW(hwnd, GWLP_USERDATA,
                        reinterpret_cast<LONG_PTR>(reinterpret_cast<CREATESTRUCTW *>(lparam)->lpCreateParams));
    auto *self = reinterpret_cast<Win32Ui *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (!self)
      return DefWindowProcW(hwnd, message, wparam, lparam);
    switch (message) {
    case WM_PAINT: {
      PAINTSTRUCT ps;
      HDC dc = BeginPaint(hwnd, &ps);
      RECT client;
      GetClientRect(hwnd, &client);
      self->paint(dc, client);
      EndPaint(hwnd, &ps);
      return 0;
    }
    case WM_ERASEBKGND:
      return 1;
    case WM_TIMER:
      if (self->total_ == 0)
        InvalidateRect(hwnd, nullptr, FALSE);
      return 0;
    case WM_MOUSEMOVE: {
      const RECT button = self->cancelRect();
      const POINT point{static_cast<short>(LOWORD(lparam)), static_cast<short>(HIWORD(lparam))};
      const bool hover = PtInRect(&button, point);
      if (hover != self->hover_) {
        self->hover_ = hover;
        InvalidateRect(hwnd, nullptr, FALSE);
      }
      return 0;
    }
    case WM_LBUTTONUP: {
      const RECT button = self->cancelRect();
      const POINT point{static_cast<short>(LOWORD(lparam)), static_cast<short>(HIWORD(lparam))};
      if (PtInRect(&button, point)) {
        self->cancel_ = true;
        InvalidateRect(hwnd, nullptr, FALSE);
      }
      return 0;
    }
    case WM_DPICHANGED: {
      self->scale_ = HIWORD(wparam) / 96.0;
      const RECT *suggested = reinterpret_cast<RECT *>(lparam);
      SetWindowPos(hwnd, nullptr, suggested->left, suggested->top, suggested->right - suggested->left,
                   suggested->bottom - suggested->top, SWP_NOZORDER);
      return 0;
    }
    case WM_CLOSE:
      // The title bar X means cancel; the installer closes the window itself.
      self->cancel_ = true;
      InvalidateRect(hwnd, nullptr, FALSE);
      return 0;
    case kCloseMessage:
      DestroyWindow(hwnd);
      return 0;
    case WM_DESTROY:
      PostQuitMessage(0);
      return 0;
    }
    return DefWindowProcW(hwnd, message, wparam, lparam);
  }

  std::mutex mutex_;
  std::condition_variable ready_;
  std::thread thread_;
  HWND hwnd_ = nullptr;
  bool started_ = false;
  double scale_ = 1.0;
  bool hover_ = false;
  std::atomic<bool> cancel_{false};
  std::wstring headline_ = L"Installing Orchard";
  std::wstring status_ = L"Preparing";
  std::uint64_t done_ = 0, total_ = 0;
};

} // namespace

std::unique_ptr<ProgressUi> createWin32Ui() { return std::make_unique<Win32Ui>(); }

} // namespace orchard::boot
