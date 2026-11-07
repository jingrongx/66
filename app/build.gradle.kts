plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lvyoubao.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lvyoubao.app"
        minSdk = 21 // 覆盖比亚迪宋等 Android 5.1（API 22）车机
        targetSdk = 35
        versionCode = 55
        versionName = "0.8.33"
    }

    signingConfigs {
        create("release") {
            // CI 注入正式签名（GitHub Secrets）；本地/无密钥环境回退 debug 签名
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (!ksFile.isNullOrBlank()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // API 26 以下没有 java.time，靠脱糖补齐（AskScreen/WeatherClient 用到）
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    testImplementation("junit:junit:4.13.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 扫码同步：二维码生成与识别（纯 Java 实现无厂商依赖，兼容 API 21+）
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 离线矢量地图（开源 OSM 生态，推演方案选型）——mapsforge 0.21.0 的 Maven 变体解析
    // 在 AGP 消费端不可靠（依赖图可见但字节码不可见），改为 vendor jar 保证确定性
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("com.caverock:androidsvg:1.4")
    // 农历/干支生肖/节气（精确到秒）/节日本地推算：大模型口算农历节气不可靠，注入本地精确数据
    implementation("com.github.6tail:lunar-java:1.7.8")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
