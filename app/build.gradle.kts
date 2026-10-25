plugins {
    id("com.android.application")
}

android {
    namespace = "org.example.linereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.example.linereader"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
