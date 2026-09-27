# Apache POI
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn javax.xml.stream.**
-dontwarn org.openxmlformats.**

# Google API Client
-keep class com.google.api.** { *; }
-dontwarn com.google.api.**
-keep class com.google.http.** { *; }
-dontwarn com.google.http.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
