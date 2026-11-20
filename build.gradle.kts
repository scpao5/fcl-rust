// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}

// AGP 8.13 捆绑的 D8/R8 8.13.19 只支持到 Kotlin 2.3，
// Kotlin 2.4 产物需 R8 9.1.29+（https://developer.android.com/studio/build/kotlin-d8-r8-versions），
// 通过 buildscript classpath 覆盖捆绑版本
buildscript {
    dependencies {
        classpath("com.android.tools:r8:9.4.17")
    }
}
// /storage 挂载为 noexec：CMake 与 prefab 生成的脚本无法在那里执行，所以所有模块的构建
// 输出都放到 $HOME（可执行的文件系统）。
val buildHome = System.getenv("HOME") ?: "/data/data/com.termux/files/home"
allprojects {
    layout.buildDirectory.set(File("$buildHome/slbuild/${rootProject.name}/${project.path.replace(':', '_')}"))
}

// 签名：只引用属性，值由 Gradle 自己读（构建机上配在 ~/.gradle/gradle.properties 或
// 环境变量里）。这个脚本从不读取、也不打印它们。
subprojects {
    afterEvaluate {
        val android = extensions.findByName("android")
            as? com.android.build.gradle.internal.dsl.BaseAppModuleExtension ?: return@afterEvaluate
        val store = providers.gradleProperty("KEYSTORE_FILE").orNull
        val password = providers.gradleProperty("KEYSTORE_PASSWORD").orNull
        val alias = providers.gradleProperty("KEY_ALIAS").orNull
        val keyPwd = providers.gradleProperty("KEY_PASSWORD").orNull
        if (store == null || password == null || alias == null) {
            logger.warn("no signing key in the Gradle properties: ${project.path} stays unsigned")
            return@afterEvaluate
        }
        android.signingConfigs.apply {
            create("starling").apply {
                storeFile = rootProject.file(store)
                storePassword = password
                keyAlias = alias
                keyPassword = keyPwd ?: password
            }
        }
        android.buildTypes.configureEach {
            signingConfig = android.signingConfigs.getByName("starling")
        }
    }
}
