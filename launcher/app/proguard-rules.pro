# Shrink and optimise: keep launcher class names, source files, and line numbers readable in stack traces.
-keep,allowshrinking,allowoptimization class com.gathin.tvhome.**
-keepattributes SourceFile,LineNumberTable
