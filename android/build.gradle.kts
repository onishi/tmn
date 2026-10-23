plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9以降はKotlinサポートが組み込みのため、appモジュールでは適用しない。
    // ここではAGPが使うKotlin Gradle Pluginのバージョン指定のためだけに宣言する
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
