# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Temp fix for androidx.window:window:1.0.0-alpha09 imported by termux-shared
# https://issuetracker.google.com/issues/189001730
# https://android-review.googlesource.com/c/platform/frameworks/support/+/1757630
-keep class androidx.window.** { *; }

# Netty BlockHound integration - BlockHound is optional and may not be present
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Keep Netty classes that may be accessed via reflection
-keep class io.netty.** { *; }

# Optional Netty dependencies that may not be present - ignore them during R8 processing
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.barchart.udt.**
-dontwarn com.fasterxml.aalto.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn com.sun.nio.sctp.**
-dontwarn gnu.io.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn javax.naming.**
-dontwarn javax.xml.stream.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.jboss.marshalling.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.slf4j.**
-dontwarn sun.security.x509.**
