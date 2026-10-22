import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9.0 起内置 Kotlin 支持，**不能再应用** org.jetbrains.kotlin.android，
    // 否则报错原文：`The 'org.jetbrains.kotlin.android' plugin is no longer required for
    // Kotlin support since AGP 9.0.`（详见 https://kotl.in/gradle/agp-built-in-kotlin）
    // Kotlin 源码版本由 AGP 内置 toolchain 决定，见下方 compileOptions。
    // Compose 编译器插件仍需单独应用。
    alias(libs.plugins.kotlin.compose)
}

// 签名配置从 keystore.properties 读（该文件被 .gitignore 排除，口令不随仓库外泄）。
// 没有这个文件时 release 不签名，debug 照常能构建 —— 不把签名做成硬前置。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ww.kkexi"
    // 37 不是随便挑的：Compose BOM 2026.09.00 里的 compose-ui / foundation / runtime 1.12.1
    // 硬性要求 compileSdk >= 37 且 AGP >= 9.1.0（实测报错原文见 README「踩过的坑」）。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ww.kkexi"
        minSdk = 26
        // targetSdk 保持 36：这决定运行时行为，与「能编译」是两件事，不跟着一起跳。
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// 单元测试的执行方式。
//
// 背景（实测）：本机 DSH 沙箱不允许子进程打开**命名管道**。Gradle 的测试任务会 fork 一个
// Test Executor 进程、并用命名管道把 classpath 喂给它，于是必然失败：
//     java.io.IOException: 管道正在被关闭
//     ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
// 这不是代码问题，也不是重试能解决的（属于沙箱边界）；`setForkEvery(0)` 也压不住 AGP 的测试任务。
//
// 对策：**不让 Gradle 跑测试**。用下面的任务把测试运行时 classpath 写到一个文本文件，
// 再由 `_tools/run-tests.ps1` 用工作区内的 JDK 21 直接调 JUnit 跑 —— 那条路径不经过命名管道。
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/** 导出测试运行时 classpath，供 `_tools/run.py` / `run-tests.ps1` 在沙箱外跑 JUnit。 */
val dumpTestClasspath by tasks.registering {
    val output = layout.buildDirectory.file("test-classpath.txt")
    val runtime = configurations.named("debugUnitTestRuntimeClasspath")
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        // 必须**显式**指定 UTF-8 与 \n：
        //   - 编码：默认 charset 在中文 Windows 上是 GBK，而本项目路径可能含中文，
        //     GBK 写出去、UTF-8 读进来就是乱码，
        //     表现为 JUnit 报 ClassNotFoundException；反过来也一样。
        //   - 行尾：项目硬约束要求所有文本文件 LF。
        file.writeText(
            runtime.get().joinToString("\n") { it.absolutePath } + "\n",
            Charsets.UTF_8,
        )
        println("test classpath -> ${file.absolutePath}  (${runtime.get().files.size} entries)")
    }
}
