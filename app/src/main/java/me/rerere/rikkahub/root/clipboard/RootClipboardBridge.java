package me.rerere.rikkahub.root.clipboard;

import android.content.ClipData;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A bounded, root-launched app_process entry point. Clipboard payloads stay in memory and cross
 * only an authenticated local socket; stdout, shell arguments and files never contain them.
 * Android permits its shell UID to read the clipboard without foreground focus. Only Binder
 * clipboard calls temporarily use that UID; input subprocesses continue to run as root.
 */
@Keep
public final class RootClipboardBridge {
    public static final String PROTOCOL = "RIKKA_ROOT_CLIPBOARD_1";
    public static final int MAX_TEXT_BYTES = 512 * 1024;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int SHELL_UID = 2000;
    private static final String[] INPUT_COMMANDS = {
            "input keycombination 113 29", "input keyevent 67", "input keyevent 279"
    };

    private RootClipboardBridge() {}

    public interface ClipboardAccess {
        ClipData get() throws Exception;
        void set(ClipData value) throws Exception;
        default boolean canClear() { return true; }
    }

    public interface Commands {
        /** Null on success; otherwise a payload-free error code. */
        String execute(String command) throws Exception;
    }

    /** The whole old ClipData is retained, rather than flattening HTML, intents and extra items. */
    public static String replaceText(ClipboardAccess clipboard, String text, Commands commands) throws Exception {
        final ClipData previous;
        try {
            previous = clipboard.get();
        } catch (Exception ignored) {
            return "root_clipboard_unavailable";
        }
        // Replacing a clip revokes its temporary URI grants. Without another Android privilege,
        // there is no reliable general way to re-grant a photo owned by a different application.
        if (containsContentUri(previous)) return "root_clipboard_uri_restore_unsupported";
        if (previous == null && !clipboard.canClear()) return "root_clipboard_clear_unsupported";
        final ClipData temporary = ClipData.newPlainText("rikka-root-paste-" + UUID.randomUUID(), text);
        final PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        temporary.getDescription().setExtras(extras);
        String result = null;
        boolean installed = false;
        try {
            installed = true;
            clipboard.set(temporary);
            if (!owns(clipboard.get(), temporary)) result = "root_clipboard_unavailable";
            else for (String command : INPUT_COMMANDS) {
                // A concurrent user copy must never be pasted into the model-selected field.
                if (!owns(clipboard.get(), temporary)) { result = "root_clipboard_changed"; break; }
                result = commands.execute(command);
                if (result != null) break;
            }
        } finally {
            if (installed) {
                try {
                    // Respect a user's copy made while the field was being edited.
                    if (owns(clipboard.get(), temporary)) clipboard.set(previous);
                } catch (Exception ignored) {
                    // No payload or exception text leaves the bridge.
                    result = "root_clipboard_restore_failed";
                }
            }
        }
        return result;
    }

    private static boolean containsContentUri(ClipData clip) {
        if (clip == null) return false;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null && "content".equals(item.getUri().getScheme())) return true;
            if (item.getIntent() != null) {
                if (item.getIntent().getData() != null && "content".equals(item.getIntent().getData().getScheme())) return true;
                if (containsContentUri(item.getIntent().getClipData())) return true;
            }
        }
        return false;
    }

    private static boolean owns(ClipData current, ClipData temporary) {
        return current != null && current.getItemCount() == 1 && current.getDescription().getLabel() != null
                && temporary.getDescription().getLabel().toString().contentEquals(current.getDescription().getLabel())
                && current.getItemAt(0).getText() != null
                && temporary.getItemAt(0).getText().toString().contentEquals(current.getItemAt(0).getText());
    }

    public static void main(String[] args) {
        if (args.length != 3) return;
        try (LocalSocket socket = new LocalSocket()) {
            if (Os.getuid() != 0 || Os.geteuid() != 0) return;
            final int appUid = Integer.parseInt(args[1]);
            final int userId = Integer.parseInt(args[2]);
            socket.connect(new LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT));
            if (socket.getPeerCredentials().getUid() != appUid) return;
            socket.setSoTimeout(10_000);
            final DataInputStream input = new DataInputStream(socket.getInputStream());
            final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(PROTOCOL);
            output.flush();
            final int length = input.readInt();
            if (length < 0 || length > MAX_TEXT_BYTES) return;
            final byte[] bytes = new byte[length];
            input.readFully(bytes);
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final String result;
            final long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            try {
                result = replaceText(new BinderClipboard(userId), text, command -> {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) return "command_timeout";
                    socket.setSoTimeout((int) Math.min(10_000, remaining));
                    output.writeUTF("authorize");
                    output.writeUTF(command);
                    output.flush();
                    if (!input.readBoolean()) return "screen_permission_revoked";
                    remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) return "command_timeout";
                    return runInput(command, Math.min(5_000, remaining)) ? null : "root_input_failed";
                });
            } catch (Exception ignored) {
                // Socket EOF (including app cancellation/death) still restores in replaceText.finally.
                return;
            }
            output.writeUTF("result");
            output.writeUTF(result == null ? "ok" : result);
            output.flush();
        } catch (Exception ignored) {
            // app_process failures never print clipboard values, Binder exceptions or stack traces.
        }
    }

    private static boolean runInput(String command, long timeoutMs) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command.split(" ")).redirectErrorStream(true).start();
        final Thread drain = new Thread(() -> {
            try {
                final byte[] buffer = new byte[1024];
                while (process.getInputStream().read(buffer) != -1) { }
            } catch (IOException ignored) { }
        }, "root-paste-input-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return false;
            return process.exitValue() == 0;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Select actual AIDL signatures, never guessed transaction numbers or OEM cmd subcommands. */
    static final class BinderClipboard implements ClipboardAccess {
        private final Object service;
        private final Class<?> interfaceType;
        private final int userId;

        BinderClipboard(int userId) throws Exception {
            this.userId = userId;
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "clipboard");
            // An OEM without the shell's standard background clipboard privilege can return null
            // for a nonempty clip. Fail before mutation instead of mistaking denial for empty.
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                IBinder packageBinder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "package");
                Class<?> packageStub = Class.forName("android.content.pm.IPackageManager$Stub");
                Object packages = packageStub.getMethod("asInterface", IBinder.class).invoke(null, packageBinder);
                Method checkPermission = Class.forName("android.content.pm.IPackageManager")
                        .getMethod("checkPermission", String.class, String.class, int.class);
                Object granted = checkPermission.invoke(packages, "android.permission.READ_CLIPBOARD_IN_BACKGROUND", SHELL_PACKAGE, userId);
                if (!Integer.valueOf(0).equals(granted)) throw new IOException("clipboard_background_unavailable");
            }
            IBinder trustBinder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "trust");
            Class<?> trustStub = Class.forName("android.app.trust.ITrustManager$Stub");
            Object trust = trustStub.getMethod("asInterface", IBinder.class).invoke(null, trustBinder);
            boolean checkedLock = false;
            for (Method method : Class.forName("android.app.trust.ITrustManager").getMethods()) {
                if (!"isDeviceLocked".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                final Object locked;
                if (types.length == 1 && types[0] == int.class) locked = method.invoke(trust, userId);
                else if (types.length == 2 && types[0] == int.class && types[1] == int.class) locked = method.invoke(trust, userId, 0);
                else continue;
                if (Boolean.TRUE.equals(locked)) throw new IOException("clipboard_locked");
                checkedLock = true;
                break;
            }
            if (!checkedLock) throw new IOException("clipboard_lock_check_unavailable");
            Class<?> stub = Class.forName("android.content.IClipboard$Stub");
            service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            interfaceType = Class.forName("android.content.IClipboard");
            if (service == null) throw new IOException("clipboard_unavailable");
        }

        @Override public ClipData get() throws Exception {
            return (ClipData) invoke("getPrimaryClip", null, false);
        }

        @Override public void set(ClipData clip) throws Exception {
            if (clip == null) {
                if (!canClear()) throw new IOException("clipboard_clear_unsupported");
                invoke("clearPrimaryClip", null, false);
            } else invoke("setPrimaryClip", clip, true);
        }

        @Override public boolean canClear() { return hasMethod("clearPrimaryClip"); }

        private boolean hasMethod(String name) {
            for (Method method : interfaceType.getMethods()) if (name.equals(method.getName())) return true;
            return false;
        }

        private Object invoke(String name, ClipData clip, boolean setter) throws Exception {
            final Method method = findClipboardMethod(interfaceType, name, setter);
            final Object[] arguments = clipboardArguments(method.getParameterTypes(), clip, setter, userId);
            // Keep real/saved UID 0 so returning to root does not require another su launch.
            Os.seteuid(userId * 100000 + SHELL_UID);
            try {
                return method.invoke(service, arguments);
            } catch (InvocationTargetException error) {
                throw new IOException("clipboard_binder_failed");
            } finally {
                Os.seteuid(0);
            }
        }
    }

    static Method findClipboardMethod(Class<?> type, String name, boolean setter) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!name.equals(method.getName())) continue;
            try {
                clipboardArguments(method.getParameterTypes(), null, setter, 0);
                return method;
            } catch (IllegalArgumentException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    static Object[] clipboardArguments(Class<?>[] types, ClipData clip, boolean setter, int userId) {
        final int start = setter ? 1 : 0;
        if (types.length < start + 1 || (setter && types[0] != ClipData.class) || types[start] != String.class)
            throw new IllegalArgumentException("unsupported_clipboard_signature");
        final Object[] arguments = new Object[types.length];
        if (setter) arguments[0] = clip;
        arguments[start] = SHELL_PACKAGE;
        int index = start + 1;
        if (index < types.length && types[index] == String.class) arguments[index++] = null; // attributionTag
        if (index < types.length && types[index] == int.class) arguments[index++] = userId;
        if (index < types.length && types[index] == int.class) arguments[index++] = 0; // primary device
        if (index != types.length) throw new IllegalArgumentException("unsupported_clipboard_signature");
        return arguments;
    }
}
