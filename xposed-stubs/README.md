Compile-only stand-ins for the legacy Xposed API (`de.robv.android.xposed`, API 82).
LSPosed provides the real classes at runtime. `build.sh` compiles against these but never
puts them in the APK. Only the members Cabin uses are declared; names and signatures must
match the real API.
