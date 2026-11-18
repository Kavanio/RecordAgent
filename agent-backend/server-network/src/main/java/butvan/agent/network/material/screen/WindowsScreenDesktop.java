package butvan.agent.network.material.screen;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Wtsapi32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Windows 只允许后端所在活动会话的默认输入桌面。 */
final class WindowsScreenDesktop {
    private WindowsScreenDesktop() { }

    static void requireUnlockedSession() {
        var current = new IntByReference();
        var kernel32 = Kernel32.INSTANCE;
        if (!kernel32.ProcessIdToSessionId(kernel32.GetCurrentProcessId(), current)
                || current.getValue() == 0 || !isSessionActive(current.getValue())) {
            throw new IllegalStateException("截图需要 Windows 后端在已登录且仍连接的交互桌面会话中运行。");
        }
        // DESKTOP_READOBJECTS 和 UOI_NAME 只用于识别活动输入桌面的名称。
        Pointer desktop = Api.USER.OpenInputDesktop(0, false, 0x0001);
        if (desktop == null) throw unavailable();
        try {
            char[] name = new char[128];
            if (!Api.USER.GetUserObjectInformationW(desktop, 2, name, name.length * Native.WCHAR_SIZE,
                    new IntByReference()) || !"Default".equalsIgnoreCase(Native.toString(name))) {
                throw unavailable();
            }
        } finally {
            Api.USER.CloseDesktop(desktop);
        }
    }

    private static boolean isSessionActive(int sessionId) {
        var server = Wtsapi32.INSTANCE;
        var buffer = new PointerByReference();
        var length = new IntByReference();
        if (!server.WTSQuerySessionInformation(Wtsapi32.WTS_CURRENT_SERVER_HANDLE, sessionId,
                Wtsapi32.WTS_INFO_CLASS.WTSConnectState, buffer, length)) return false;
        Pointer state = buffer.getValue();
        try {
            return state != null && length.getValue() >= Integer.BYTES
                    && state.getInt(0) == Wtsapi32.WTS_CONNECTSTATE_CLASS.WTSActive;
        } finally {
            if (state != null) server.WTSFreeMemory(state);
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Windows 桌面已锁定或当前没有可捕获的输入桌面。");
    }

    interface Api extends StdCallLibrary {
        Api USER = Native.load("user32", Api.class);

        Pointer OpenInputDesktop(int flags, boolean inherit, int desiredAccess);
        boolean GetUserObjectInformationW(Pointer desktop, int index, char[] value, int bytes, IntByReference needed);
        boolean CloseDesktop(Pointer desktop);
    }
}
