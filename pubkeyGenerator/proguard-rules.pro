# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep PubkeyUtils and GeneratePubkeyActivity
-keep class com.iiordanov.pubkeygenerator.** { *; }

# Keep trilead SSH2 classes used by PubkeyUtils
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**
