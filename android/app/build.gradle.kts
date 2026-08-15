plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.wagaya.tmn.caster"
    // Android 16(API 36)まで対応。API 37(Android 17)はACCESS_LOCAL_NETWORK権限が
    // 必須化されWebRTCのローカルP2P接続に影響しうるため、様子を見て別途対応する
    // (docs/android-14-17-support.md参照)。targetSdkが36以下でもAndroid 17端末上では
    // 後方互換の挙動で問題なく動作する
    compileSdk = 36

    defaultConfig {
        applicationId = "org.wagaya.tmn.caster"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // .tfliteモデルファイルはAAPTの圧縮対象から除外する(MediaPipeがアセットを
        // 直接読み込む際に、圧縮されたアセットだとエラーになるケースを避けるため)
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // カメラ映像取得
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // WebRTC (Google公式SDKはメンテ終了のため、コミュニティメンテのビルドを使用)
    implementation("io.github.webrtc-sdk:android:125.6422.07.1")

    // 猫・人検知(オンデバイス物体検出)。TensorFlow Lite Task Libraryはメンテナンスモードで
    // GoogleがMediaPipe Tasksへの移行を推奨しているため、こちらを採用する
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // シグナリングWebSocketクライアント
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 常時接続の再接続制御
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
