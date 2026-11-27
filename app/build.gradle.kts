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
        versionCode = 33
        versionName = "1.28"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ký bằng khóa debug để cài được ngay, không cần tạo keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // mô hình AI phân lớp da phải để nguyên (không nén) mới đọc được
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        // ML Kit và MediaPipe có thể cùng mang thư viện C++ dùng chung
        jniLibs.pickFirsts += setOf("lib/*/libc++_shared.so")
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
    // Tách người khỏi nền để dựng bản đồ da (mô hình nằm sẵn trong app, chạy offline)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // Phân lớp da mặt / da người / tóc / áo (MediaPipe, offline; mô hình tải về lúc build)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
