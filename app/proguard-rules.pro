# R8 keep rules for LS4TC421 (Q187).
#
# Nothing here so far: the app uses no reflection of its own. The program files are read and written
# by hand-written code (model/ProgramFile.kt), not by a reflection-based JSON library. Room, DataStore,
# Compose, Navigation and the coroutines ship their own consumer rules inside their artifacts.
#
# Keep the line numbers in stack traces, without the source file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
