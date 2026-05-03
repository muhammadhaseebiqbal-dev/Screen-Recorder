plugins {
    id("com.android.application")
}

android {
    namespace = "com.haseeb.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haseeb.recorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "3.1"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
   // implementation("androidx.core:core-ktx:1.19.0-alpha01")
    implementation("androidx.appcompat:appcompat:1.8.0-alpha01")
    implementation("com.google.android.material:material:1.14.0-beta01")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.github.bumptech.glide:glide:5.0.5")
}