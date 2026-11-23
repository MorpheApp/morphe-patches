-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class com.google.** {
  *;
}
# QuackJS. It uses no reflection, so this is likely not needed and R8 could shrink it.
# Kept for now until the library is tested with shrinking.
-keep class meow.nyx.** {
  *;
}
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
