# Charger Greetings -- R8 rules.
#
# The app has no reflection, no serialization and no JNI, so the defaults do
# almost everything. Only the manifest-declared receivers need protecting:
# they are instantiated by name by the system, never referenced from code.

-keep class com.chargergreetings.app.power.PowerEventReceiver { <init>(); }
-keep class com.chargergreetings.app.power.BootReceiver { <init>(); }

# Keep line numbers so a crash report from a release build is readable,
# while still stripping the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
