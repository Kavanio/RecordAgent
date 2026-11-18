package butvan.agent.network.material.screen;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/** macOS 仅允许已登录且未锁定的本机图形会话。 */
final class MacScreenDesktop {
    /** CGSession.h 中 kCGSessionOnConsoleKey 宏展开后的字典键。 */
    private static final String ON_CONSOLE_KEY = "kCGSSessionOnConsoleKey";

    private MacScreenDesktop() { }

    static void requireUnlockedConsole() {
        Pointer info = Graphics.API.CGSessionCopyCurrentDictionary();
        if (info == null) throw unavailable();
        try {
            if (!flag(info, ON_CONSOLE_KEY) || !flag(info, "kCGSessionLoginDoneKey")) {
                throw unavailable();
            }
            if (flag(info, "CGSSessionScreenIsLocked")) {
                throw new IllegalStateException("macOS 桌面已锁定，请解锁后再截图。");
            }
        } finally {
            Foundation.API.CFRelease(info);
        }
    }

    private static boolean flag(Pointer dictionary, String key) {
        var api = Foundation.API;
        Pointer string = api.CFStringCreateWithCString(null, key, 0x08000100);
        if (string == null) throw unavailable();
        try {
            Pointer value = api.CFDictionaryGetValue(dictionary, string);
            return value != null && api.CFBooleanGetValue(value) != 0;
        } finally {
            api.CFRelease(string);
        }
    }

    static boolean hasCapturePermission() {
        return Graphics.API.CGPreflightScreenCaptureAccess() != 0;
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("后端进程当前没有已登录的 macOS 本机图形会话。");
    }

    interface Graphics extends Library {
        Graphics API = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Graphics.class);
        Pointer CGSessionCopyCurrentDictionary();
        byte CGPreflightScreenCaptureAccess();
    }

    interface Foundation extends Library {
        Foundation API = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Foundation.class);
        Pointer CFStringCreateWithCString(Pointer allocator, String key, int encoding);
        Pointer CFDictionaryGetValue(Pointer dictionary, Pointer key);
        byte CFBooleanGetValue(Pointer value);
        void CFRelease(Pointer value);
    }
}
