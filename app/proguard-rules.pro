# R8 rules for release builds.

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization models used by the Google Calendar REST client.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.deeproot.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.deeproot.app.**$$serializer { *; }

# Credential Manager (Google Sign-In) uses reflection to find the Play Services provider.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

# Retrofit interfaces (Retrofit ships its own rules; keep our API interface signatures).
-keep,allowobfuscation interface com.deeproot.app.data.calendar.CalendarApi
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
