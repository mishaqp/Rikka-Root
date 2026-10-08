# JSch selects algorithms/authentication by class name, including package-private constructors.
# Mandatory release compatibility for the Agent SSH port under Rikka-Root's R8 optimization.
-keep,allowoptimization class com.jcraft.jsch.** { *; }

# Optional JSch providers/adapters are absent on Android and are not enabled by these tools.
# JSch probes unavailable BC algorithms and falls back; SSH explicitly excludes GSS auth.
-dontwarn org.bouncycastle.crypto.**
-dontwarn org.bouncycastle.pqc.crypto.ntruprime.**
-dontwarn org.ietf.jgss.**
# Unused Windows Pageant, junixsocket and Log4j adapters shipped in the same upstream jar.
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
