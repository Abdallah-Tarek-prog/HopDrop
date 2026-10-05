# HopDrop has no reflection-based code; Android keeps the manifest's components on its own.
# ZXing's core decoder is fine with shrinking. Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
