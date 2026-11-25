# Drawspace ProGuard / R8 rules

# Keep kotlinx.serialization generated serializers and sealed/enum metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class space.owork.draw.** {
    *** Companion;
}
-keepclasseswithmembers class space.owork.draw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class space.owork.draw.**$$serializer { *; }
-keepclassmembers enum space.owork.draw.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JGit reflection targets: NLS translation bundles populate public fields via reflection,
# and FS / ObjectChecker / FileRepositoryBuilder use reflective & ServiceLoader lookups.
-keep class * extends org.eclipse.jgit.nls.TranslationBundle {
    public <fields>;
    public <init>();
}
-keep class org.eclipse.jgit.nls.** { *; }
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class org.eclipse.jgit.util.FS* { *; }
-keep class org.eclipse.jgit.lib.Config$SectionParser { *; }
-keep class * implements org.eclipse.jgit.lib.Config$SectionParser { *; }

# Suppress missing optional desktop JDK / transport references in JGit & SLF4J
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn java.nio.file.attribute.**
