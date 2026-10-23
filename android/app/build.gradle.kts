plugins {
    id("com.android.application")
}

android {
    namespace = "org.wagaya.tmn.caster"
    // targetSdkはAndroid 16(API 36)まで対応。API 37(Android 17)はACCESS_LOCAL_NETWORK権限が
    // 必須化されWebRTCのローカルP2P接続に影響しうるため、様子を見て別途対応する
    // (docs/android-14-17-support.md参照)。targetSdkが36以下でもAndroid 17端末上では
    // 後方互換の挙動で問題なく動作する。
    // compileSdkはcore-ktx 1.19・OkHttp 5.5等の依存ライブラリが37以上を要求するため37にする
    // (compileSdkは利用可能なAPIを増やすだけで、実行時の挙動はtargetSdkで決まる)
    compileSdk = 37

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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    // カメラ映像取得
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")

    // WebRTC (Google公式SDKはメンテ終了のため、コミュニティメンテのビルドを使用)
    implementation("io.github.webrtc-sdk:android:150.7871.01")

    // 猫・人検知(オンデバイス物体検出)。TensorFlow Lite Task Libraryはメンテナンスモードで
    // GoogleがMediaPipe Tasksへの移行を推奨しているため、こちらを採用する
    implementation("com.google.mediapipe:tasks-vision:1.1.1")

    // シグナリングWebSocketクライアント
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // 常時接続の再接続制御
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
