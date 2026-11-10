# Adapted from r-i-s-u/NewPipe app/proguard-rules.pro (commit 8884ce5d0e3d31be61b239fae5f7140fd3802b87).
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
