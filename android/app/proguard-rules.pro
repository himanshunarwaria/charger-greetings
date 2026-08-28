# Charger Greetings -- R8 rules.
#
# Our own code uses no reflection, but two things reach it by name anyway:
# the manifest-declared receivers, and the libraries below. Anything the JVM
# never sees called is fair game for R8 to delete, so each of those needs a
# rule.

-keep class com.chargergreetings.app.power.PowerEventReceiver { <init>(); }
-keep class com.chargergreetings.app.power.BootReceiver { <init>(); }

# --- WorkManager's Room database ------------------------------------------
#
# This one rule is the difference between the app opening and not opening.
#
# WorkManager keeps its job queue in a Room database, and Room creates the
# generated WorkDatabase_Impl reflectively: Class.forName(name + "_Impl")
# followed by getDeclaredConstructor(). room-runtime 2.6.1 -- what
# work-runtime 2.10.0 pulls in -- ships only:
#
#     -keep class * extends androidx.room.RoomDatabase
#
# with no member specification. Under R8 *full mode*, the default since AGP 8,
# that keeps the class but NOT its members, so the no-arg constructor is
# deleted. Room's reflective lookup then throws NoSuchMethodException from
# WorkManager's androidx.startup initializer -- which runs inside
# handleBindApplication, before Application.onCreate and before any of our
# code -- so the process dies on launch every single time. That was the
# v1.2.0 launch crash, reproduced on an Android 36 emulator.
#
# Adding the member specification ourselves fixes it independently of which
# Room version any future dependency drags in.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Keep line numbers so a crash report from a release build is readable,
# while still stripping the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
