package butvan.agent.network.material.screen;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/** macOS 仅允许已登录且未锁定的本机图形会话。 */
final class MacScreenDesktop {
    private MacScreenDesktop() { }

    static void requireUnlockedConsole() {
        Pointer info = Graphics.API.CGSessionCopyCurrentDictionary();
        if (info == null) throw unavailable();
        try {
            if (!flag(info, "kCGSessionOnConsoleKey") || !flag(info, "kCGSessionLoginDoneKey")
                    || flag(info, "CGSSessionScreenIsLocked")) {
                throw unavailable();
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
        return new IllegalStateException("macOS 桌面已锁定或当前没有已登录的本机图形会话。");
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
