plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.looknote.exif"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.looknote.exif"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "1.15"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ký bằng khóa debug để cài được ngay, không cần tạo keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Nhận diện khuôn mặt chạy offline (mô hình nằm sẵn trong app)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
