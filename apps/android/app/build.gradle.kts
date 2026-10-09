import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

val localSigningEnvFile = file(
    "${System.getProperty("user.home")}/Library/Application Support/DSH Links Signing/env"
)

fun loadLocalSigningEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    val result = mutableMapOf<String, String>()
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val body = line.removePrefix("export ").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return@forEach
        val key = body.substring(0, eq).trim()
        var value = body.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        result[key] = value
    }
    return result
}

val localSigningEnv = loadLocalSigningEnv(localSigningEnvFile)
fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localSigningEnv[name]?.takeIf { it.isNotBlank() }

val releaseSigningEnv = listOf(
    "DSH_LINKS_KEYSTORE_PATH",
    "DSH_LINKS_KEYSTORE_PASSWORD",
    "DSH_LINKS_KEY_ALIAS",
    "DSH_LINKS_KEY_PASSWORD",
).associateWith { signingValue(it) }
val releaseSigningPresent = releaseSigningEnv.values.count { it != null }
check(releaseSigningPresent == 0 || releaseSigningPresent == 4) {
    val missing = releaseSigningEnv.filterValues { it == null }.keys.joinToString()
    "Incomplete release signing environment; missing: $missing"
}
val releaseSigningReady = releaseSigningPresent == 4
val allowUnsignedRelease =
    providers.gradleProperty("allowUnsignedRelease").orNull == "true"

// ---------- 内部构建元数据（方案 §4 C00） ----------
// 手机同步合同版本，与 docs/MOBILE_SYNC_CONTRACT.md / scripts/build-metadata.mjs 保持一致。
val DSH_CONTRACT_VERSION = 1

/**
 * 短提交 SHA；工作树脏时带 `-dirty`。任何一步失败都回退 `unknown`，
 * 不让 CI 或离线构建因为元数据采集不到而失败。
 */
fun dshBuildCommit(project: org.gradle.api.Project): String {
    val sha =
        try {
            project.providers.exec {
                commandLine("git", "rev-parse", "--short=8", "HEAD")
            }.standardOutput.asText.get().trim()
        } catch (_: Exception) {
            ""
        }
    if (sha.isEmpty()) return "unknown"
    val dirty =
        try {
            project.providers.exec {
                commandLine("git", "status", "--porcelain")
            }.standardOutput.asText.get().trim().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    return if (dirty) "$sha-dirty" else sha
}

/** 构建日期（UTC，yyyy-MM-dd）。 */
fun dshBuildDate(): String = LocalDate.now(ZoneOffset.UTC).toString()

android {
    namespace = "dev.deeplinks"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.deeplinks"
        minSdk = 26
        targetSdk = 36
        // 营销版本与版本号保持原样，由维护者按发布阶段处理（方案 §4）。
        versionCode = 39
        versionName = "0.5.0-beta.31"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 内部构建元数据（方案 §4 C00）：让一张截图能对应到具体提交。
        // 值来自 git 与 Gradle 变体，不写死；缺 git 时回退 unknown。
        buildConfigField("String", "BUILD_COMMIT", "\"${dshBuildCommit(project)}\"")
        buildConfigField("String", "BUILD_DATE", "\"${dshBuildDate()}\"")
        buildConfigField("String", "BUILD_CONTRACT_VERSION", "\"$DSH_CONTRACT_VERSION\"")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("DSH_LINKS_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("DSH_LINKS_KEY_PASSWORD")
                // v3 签名：密钥轮换（key rotation）的前提。没有它，签名密钥一旦丢失或需要
                // 更换，就只能让用户**卸载重装** —— 那会丢掉已配对凭据与本地状态。
                // 实测旧产物（`a91562ae`）只有 v2，AGP 9.3.2 默认未开 v3，故在此显式开启。
                //
                // v1 **不开**：`minSdk = 26 ≥ 24`，Android 7.0+ 只需 v2，v1 无意义且会增大体积。
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 与签名 release 共存于同一设备（instrumented 测试直接跑 debug 变体，
            // 不必卸载用户手机上的 release 包）。
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Compose Preview Screenshot Testing：启用 screenshotTest 源集
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

val legalFiles = listOf(
    rootProject.file("LICENSE"),
    rootProject.file("THIRD_PARTY_NOTICES.md"),
)

/** 将 LICENSE / 第三方声明复制进 APK assets（Variant API 要求 DirectoryProperty 输出）。 */
abstract class CopyLegalAssets : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("legal")
        out.mkdirs()
        sources.files.forEach { f ->
            check(f.isFile) { "Missing required legal asset: ${f.path}" }
            f.copyTo(out.resolve(f.name), overwrite = true)
        }
    }
}

val copyLegalAssets = tasks.register<CopyLegalAssets>("copyLegalAssets") {
    sources.from(legalFiles)
    // 任务输出 = assets 根目录，内部再放 legal/ 子目录，保持历史打包路径 assets/legal/。
    outputDir.set(layout.buildDirectory.dir("generated/legalAssets"))
}

// AGP 9：生成的资产目录必须走 Variant API（Provider 不能直接进 srcDir）。
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets) { it.outputDir }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyLegalAssets)
}

tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady || allowUnsignedRelease) {
            "assembleRelease 需要本机签名材料（环境变量 DSH_LINKS_* 或 ~/Library/Application Support/DSH Links Signing/env）。验证 R8 可用 -PallowUnsignedRelease=true。Debug 构建不使用 Release Key。"
        }
    }
}

tasks.register("ensureReleaseSigning") {
    group = "build"
    description = "Fails unless all four DSH_LINKS_* release signing values are set."
    doFirst {
        check(releaseSigningReady) {
            "Signed release requires DSH_LINKS_KEYSTORE_PATH, DSH_LINKS_KEYSTORE_PASSWORD, DSH_LINKS_KEY_ALIAS, and DSH_LINKS_KEY_PASSWORD"
        }
        val keystore = file(releaseSigningEnv.getValue("DSH_LINKS_KEYSTORE_PATH")!!)
        check(keystore.isFile) { "Release keystore not found: ${keystore.path}" }
    }
}

/**
 * 方案 §21.1：产物文件名带上平台、版本与短 SHA ——「不靠显示名判断安装身份」。
 *
 * 目标名 `cetus-android-<versionName>-<shortSHA>.apk`。**保留** AGP 默认的
 * `app-release.apk`（CI、RELEASING 与既有脚本都按那个名字取件），只额外产出一份合规命名，
 * 因此不会让任何既有消费者失效。
 *
 * git 不可用时短 SHA 退化为 `nogit`，不让归档命名把构建搞失败。
 */
val cetusArtifactName: String by lazy {
    val shortSha = try {
        providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }
            .standardOutput.asText.get().trim().ifEmpty { "nogit" }
    } catch (_: Exception) {
        "nogit"
    }
    "cetus-android-${android.defaultConfig.versionName ?: "unknown"}-$shortSha.apk"
}

val nameReleaseArtifact = tasks.register<Copy>("nameReleaseArtifact") {
    group = "build"
    description = "把签名 release APK 另存为 cetus-android-<version>-<shortSHA>.apk（方案 §21.1）。"
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    // 写到独立目录：不能写回 AGP 自己的 outputs/apk/release，
    // 否则 Gradle 会判定与 createReleaseApkListingFileRedirect 冲突（产物顺序不确定）。
    into(layout.buildDirectory.dir("outputs/cetus"))
    rename { cetusArtifactName }
    mustRunAfter(tasks.matching { it.name == "assembleRelease" })
}

tasks.register("cetusReleaseArtifact") {
    group = "build"
    description = "assembleRelease + 合规命名副本（发版取件用这一条）。"
    dependsOn("assembleRelease", nameReleaseArtifact)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // 截图测试源集
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling.preview)
    screenshotTestImplementation(libs.screenshot.validation.api)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.mockwebserver3)
    // 本地 JVM 单测：org.json 在 android.jar stub 里不可用，需真实实现
    testImplementation(libs.org.json)
}
