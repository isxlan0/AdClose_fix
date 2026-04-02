import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.materialthemebuilder")
    id("com.google.devtools.ksp")
    id("dev.rikka.tools.autoresconfig")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

data class LspApiConfig(
    val apiVersion: Int,
    val apiDependency: Any,
    val serviceDependency: Any,
    val interfaceDependency: Any
)

val buildZoneId: ZoneId = ZoneId.of("Asia/Hong_Kong")
val buildTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
val versionCodeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyDDDHHmm")
val baseVersionName = "4.2.8"

val buildTimestamp = System.getenv("BUILD_TIMESTAMP")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { timestamp ->
        runCatching { LocalDateTime.parse(timestamp, buildTimestampFormatter) }.getOrNull()
    }
    ?: LocalDateTime.now(buildZoneId)

val lspApiConfigs = mapOf(
    "lsp100" to LspApiConfig(
        apiVersion = 100,
        apiDependency = files("libs/api-100.aar"),
        serviceDependency = files("libs/service-100.aar"),
        interfaceDependency = files("libs/interface-100.aar")
    ),
    "lsp101" to LspApiConfig(
        apiVersion = 101,
        apiDependency = "io.github.libxposed:api:101.0.0",
        serviceDependency = "io.github.libxposed:service:101.0.0",
        interfaceDependency = "io.github.libxposed:interface:101.0.0"
    )
)

autoResConfig {
    generateClass.set(true)
    generateRes.set(false)
    generatedClassFullName.set("com.close.hook.ads.util.LangList")
    generatedArrayFirstItem.set("SYSTEM")
}

materialThemeBuilder {
    themes {
        for ((name, color) in listOf(
            "Default" to "6750A4",
            "Red" to "F44336",
            "Pink" to "E91E63",
            "Purple" to "9C27B0",
            "DeepPurple" to "673AB7",
            "Indigo" to "3F51B5",
            "Blue" to "2196F3",
            "LightBlue" to "03A9F4",
            "Cyan" to "00BCD4",
            "Teal" to "009688",
            "Green" to "4FAF50",
            "LightGreen" to "8BC3A4",
            "Lime" to "CDDC39",
            "Yellow" to "FFEB3B",
            "Amber" to "FFC107",
            "Orange" to "FF9800",
            "DeepOrange" to "FF5722",
            "Brown" to "795548",
            "BlueGrey" to "607D8F",
            "Sakura" to "FF9CA8"
        )) {
            create("Material$name") {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#$color"
            }
        }
    }
    generatePalette = true
}

android {
    namespace = "com.close.hook.ads"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    flavorDimensions += "lspApi"

    signingConfigs {
        create("keyStore") {
            storeFile = file("AdClose.jks")
            keyAlias = "AdClose"
            keyPassword = "rikkati"
            storePassword = "rikkati"
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.close.hook.ads"
        minSdk = 26
        targetSdk = 35
        versionCode = calculateVersionCode(buildTimestamp)
        versionName = calculateVersionName(baseVersionName, buildTimestamp)

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("keyStore")
            
            externalNativeBuild {
                cmake {
                    cppFlags.add("-DDEBUG=0")
                }
            }
        }
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("keyStore")
            
            externalNativeBuild {
                cmake {
                    cppFlags.add("-DDEBUG=1")
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
        prefab = false
    }

    productFlavors {
        lspApiConfigs.forEach { (flavorName, config) ->
            create(flavorName) {
                dimension = "lspApi"
                buildConfigField("int", "LSP_API_VERSION", config.apiVersion.toString())
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/libs")
        }
    }
}

fun calculateVersionName(baseVersionName: String, buildTimestamp: LocalDateTime): String {
    return "$baseVersionName.${buildTimestamp.format(buildTimestampFormatter)}"
}

fun calculateVersionCode(buildTimestamp: LocalDateTime): Int {
    return buildTimestamp.format(versionCodeFormatter).toInt()
}

configurations.configureEach {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    compileOnly(libs.xposedApi)
    implementation(libs.dexkit)
    lspApiConfigs.forEach { (flavorName, config) ->
        add("${flavorName}CompileOnly", config.apiDependency)
        add("${flavorName}Implementation", config.serviceDependency)
        add("${flavorName}Implementation", config.interfaceDependency)
    }

    implementation("com.bytedance.android:shadowhook:2.0.0")

    implementation(libs.appcompat)
    implementation(libs.preferenceKtx)
    implementation(libs.constraintLayout)
    implementation(libs.recyclerviewSelection)
    implementation(libs.roomRuntime)
    ksp(libs.roomCompiler)
    implementation(libs.roomKtx)
    runtimeOnly(libs.lifecycleLiveDataKtx)
    implementation(libs.fragmentKtx)

    implementation(libs.material)
    implementation(libs.about)
    implementation(libs.fastscroll)
    implementation(libs.rikkaMaterial)
    implementation(libs.rikkaMaterialPreference)

    implementation(libs.kotlinxSerializationJson)

    implementation(libs.brotli.dec)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.appcenterAnalytics)
    implementation(libs.appcenterCrashes)
    implementation(libs.mpandroidchart)

    testImplementation("junit:junit:4.13.2")
}
