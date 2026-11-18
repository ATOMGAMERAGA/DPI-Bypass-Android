import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sürüm tek kaynaktan (gradle.properties) okunur — CI/release.yml sadece orayı
// günceller, buradaki türetme sayesinde her yere yansır.
val versionNameProp: String = providers.gradleProperty("VERSION_NAME").get()
val versionCodeProp: Int = providers.gradleProperty("VERSION_CODE").get().toInt()

// ---------------------------------------------------------------------------
// İMZALAMA — iki kaynak, şu öncelikle okunur:
//   1) keystore.properties (proje kökü, .gitignore'da — yerel release derlemesi)
//   2) ortam değişkenleri (CI: .github/workflows/release.yml)
// Hiçbiri yoksa release derlemesi HATA verir; debug anahtarına SESSİZCE düşmez.
// (Debug anahtarı her makinede/CI çalıştırmasında farklıdır: her sürüm başka bir
// imzayla çıkar, kullanıcı üzerine güncelleme kuramaz ve Play Protect uygulamayı
// "bilinmeyen geliştirici" diye işaretler. Bilinçli test için:
//   ./gradlew assembleRelease -PallowDebugSigning=true )
// Ayrıntı: docs/Imzalama-Rehberi.md
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingSetting(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSetting("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingSetting("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSetting("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSetting("keyPassword", "RELEASE_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null }

val allowDebugSigning =
    (providers.gradleProperty("allowDebugSigning").orNull ?: System.getenv("ALLOW_DEBUG_SIGNING")) == "true"

android {
    namespace = "net.atom.dpibypass"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "net.atom.dpibypass"
        minSdk = 26          // Android 8.0 — Quick Settings TileService için gerekli
        targetSdk = 35       // Android 15
        versionName = versionNameProp
        versionCode = versionCodeProp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64 öncelikli; diğerleri uyumluluk için.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // APK imza şemaları: v2/v3 modern Android'in doğruladığı şemalar,
                // v1 (JAR) eski araç/doğrulayıcılar için uyumluluk olarak açık.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            // Gerçek imzalama bilgisi varsa RESMİ imza kullanılır. Yoksa derleme
            // verifyReleaseSigning görevinde durur (bkz. dosya başı) — tek istisna
            // bilinçli olarak -PallowDebugSigning=true verilmesidir.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // Rehberin Compose arayüz testleri JVM'de Robolectric ile çalışır (cihaz gerekmez).
        unitTests { isIncludeAndroidResources = true }
    }

    lint {
        // Lint yine çalışır ve rapor üretir; ancak kozmetik bir lint "error"ı CI'ı
        // düşürmesin (native/anti-sansür yapısında yanlış-pozitifler olabilir).
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        // hev-socks5-tunnel ndk-build çıktısı ile CMake çıktısını birlikte paketle.
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Bağımlılık meta verisini APK/bundle'a gömme (F-Droid / reproducible dostu).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    // XML tema parent'ı (Theme.Material3.DayNight.*) için — Compose UI'ya ek olarak
    // pencere/splash teması bu kütüphaneden gelir.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // DataStore (ayarlar / ağ profilleri)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // DoH ve ASN lookup için hafif HTTP istemcisi
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-splashscreen:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Ağ yolu ölçümünü kendi yerel TLS test sunucumuzla sınamak için (gerçek platform yok).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Rehber sayfalarının gerçek Compose arayüz testleri (Robolectric, JVM).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// hev-socks5-tunnel native kütüphanesi ndk-build ile derlenir (kendi Android.mk
// yapısını kullanır). byedpi ise CMake ile (externalNativeBuild). İkisinin
// çıktısı da jniLibs'e girer.
// ---------------------------------------------------------------------------
// Git on Windows may check out upstream symlinks as text files. Build from an
// owned staging tree and turn header placeholders into relative includes; never
// rewrite the pinned submodules or require administrator symlink privileges.
val nativeSourceDirectory = layout.buildDirectory.dir("intermediates/nativeSources")
val prepareNativeSources = tasks.register<Sync>("prepareNativeSources") {
    from("src/main/jni") { exclude("**/.git", "**/.git/**") }
    into(nativeSourceDirectory)
    doLast {
        val root = nativeSourceDirectory.get().asFile.canonicalFile
        root.walkTopDown().filter { it.isFile && it.extension == "h" && it.length() < 180 }.forEach { header ->
            val relativeTarget = header.readText().trim()
            if (relativeTarget.matches(Regex("\\.\\./[A-Za-z0-9_./-]+"))) {
                val target = header.parentFile.resolve(relativeTarget).canonicalFile
                check(target.toPath().startsWith(root.toPath()) && target.isFile) {
                    "Invalid native header link: $header -> $relativeTarget"
                }
                header.writeText("#include \"$relativeTarget\"\n")
            }
        }
        patchHevSourceAddress(root.resolve("hev-socks5-tunnel/src"))
    }
}

// ---------------------------------------------------------------------------
// hev yaması (yalnız kopyalanan derleme ağacında; submodül değişmez):
// her oturum, uygulamanın TUN üzerindeki KAYNAK adresini ("ip|port") SOCKS5
// kullanıcı adı olarak ExitRouter'a iletir. Yönlendirici bununla Android'den
// bağlantının sahibi olan uygulamayı (UID) sorar ve "VPN uygulamaları WireGuard'a,
// DPI uygulamaları ByeDPI'a" ayrımını uygulama bazında yapar.
// Yama tutmazsa (hev güncellendi) derleme açıkça durur.
// ---------------------------------------------------------------------------
fun patchHevSourceAddress(src: File) {
    fun patch(file: File, anchor: String, replacement: String) {
        val text = file.readText()
        if (text.contains(replacement)) return
        check(text.contains(anchor)) { "hev yaması uygulanamadı: ${file.name} içinde beklenen satır yok" }
        file.writeText(text.replaceFirst(anchor, replacement))
    }
    val helper = """
        |#include <stdio.h>
        |#include <arpa/inet.h>
        |static void dpib_src_user (char *out, size_t size, const ip_addr_t *ip, u16_t port)
        |{
        |    struct sockaddr_in6 ss;
        |    char host[INET6_ADDRSTRLEN] = { 0 };
        |    memset (&ss, 0, sizeof (ss));
        |    lwip_to_sock_addr (ip, port, (struct sockaddr *)&ss);
        |    if (((struct sockaddr *)&ss)->sa_family == AF_INET)
        |        inet_ntop (AF_INET, &((struct sockaddr_in *)&ss)->sin_addr, host, sizeof (host));
        |    else
        |        inet_ntop (AF_INET6, &ss.sin6_addr, host, sizeof (host));
        |    snprintf (out, size, "%s|%u", host, (unsigned)port);
        |}
        |static int dpib_src_enabled (void)
        |{
        |    HevConfigServer *srv = hev_config_get_socks5_server ();
        |    return srv->user && 0 == strcmp (srv->user, "dpib-src");
        |}
        |""".trimMargin()
    val tcpC = src.resolve("hev-socks5-session-tcp.c")
    val udpC = src.resolve("hev-socks5-session-udp.c")
    // Kaynak adresi yalnız yapılandırmada `username: 'dpib-src'` varsa gönderilir
    // (ExitRouter çalışırken). ByeDPI kullanıcı adı yöntemini kabul etmez; hev ona
    // doğrudan bağlandığında eski davranış (kimlik doğrulamasız) aynen korunur.
    patch(
        src.resolve("hev-socks5-session.c"),
        "    if (srv->user && srv->pass) {",
        "    if (srv->user && srv->pass && 0 != strcmp (srv->user, \"dpib-src\")) {",
    )
    patch(src.resolve("hev-socks5-session-tcp.h"), "    HevRingBuffer *buffer;\n};", "    HevRingBuffer *buffer;\n    char dpib_user[80];\n};")
    patch(src.resolve("hev-socks5-session-udp.h"), "    int frames;\n};", "    int frames;\n    char dpib_user[80];\n};")
    patch(tcpC, "#include \"hev-socks5-session-tcp.h\"\n", "#include \"hev-socks5-session-tcp.h\"\n$helper")
    patch(udpC, "#include \"hev-socks5-session-udp.h\"\n", "#include \"hev-socks5-session-udp.h\"\n$helper")
    patch(
        tcpC,
        "    res = hev_socks5_client_tcp_construct_ip (&self->base, addr);\n    if (res < 0)\n        return -1;\n",
        "    res = hev_socks5_client_tcp_construct_ip (&self->base, addr);\n    if (res < 0)\n        return -1;\n" +
            "    if (dpib_src_enabled ()) {\n" +
            "        dpib_src_user (self->dpib_user, sizeof (self->dpib_user), &pcb->remote_ip, pcb->remote_port);\n" +
            "        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), self->dpib_user, \"-\");\n" +
            "    }\n",
    )
    patch(
        udpC,
        "    res = hev_socks5_client_udp_construct (&self->base, type);\n    if (res < 0)\n        return -1;\n",
        "    res = hev_socks5_client_udp_construct (&self->base, type);\n    if (res < 0)\n        return -1;\n" +
            "    if (dpib_src_enabled ()) {\n" +
            "        dpib_src_user (self->dpib_user, sizeof (self->dpib_user), &pcb->remote_ip, pcb->remote_port);\n" +
            "        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), self->dpib_user, \"-\");\n" +
            "    }\n",
    )
}

// ---------------------------------------------------------------------------
// WireGuard köprüsü (src/main/go/wgbridge): Go ile her ABI için libwgbridge.so.
// Windscribe/Proton'un WireGuard yapılandırması uygulamanın KENDİ tünelinde
// çalışır (Android aynı anda tek VPN'e izin verdiği için). Go bulunamazsa yerel
// derlemede uyarı verilip atlanır (özellik ekranda "bu derlemede yok" der);
// CI'da (CI=true) eksiklik derlemeyi durdurur.
// ---------------------------------------------------------------------------
val wgBridgeSrc = file("src/main/go/wgbridge")
val wgBridgeOut = layout.buildDirectory.dir("intermediates/wgbridgeLibs")
android.sourceSets.getByName("main").jniLibs.srcDir(wgBridgeOut)

fun findGo(): String? {
    val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "go.exe" else "go"
    val candidates = buildList {
        System.getenv("GOROOT")?.let { add(File(it, "bin/$exe")) }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it, exe)) }
        add(File("/usr/local/go/bin/$exe"))
    }
    return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
}

val buildWgBridge = tasks.register("buildWgBridge") {
    group = "build"
    description = "WireGuard→SOCKS5 köprüsünü (Go) her ABI için derler."
    inputs.dir(wgBridgeSrc)
    outputs.dir(wgBridgeOut)
    doLast {
        val go = findGo()
        val out = wgBridgeOut.get().asFile
        if (go == null) {
            if (System.getenv("CI") == "true") throw GradleException("Go bulunamadı: WireGuard köprüsü derlenemiyor (CI'da zorunlu).")
            logger.warn("UYARI: Go bulunamadı; WireGuard köprüsü (VPN Tüneli) bu derlemeye girmeyecek.")
            out.deleteRecursively()
            return@doLast
        }
        val osName = System.getProperty("os.name").lowercase()
        val hostTag = when {
            osName.startsWith("windows") -> "windows-x86_64"
            osName.contains("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val ext = if (osName.startsWith("windows")) ".cmd" else ""
        val bin = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        val abis = listOf(
            Triple("arm64-v8a", "arm64", "aarch64-linux-android26-clang"),
            Triple("armeabi-v7a", "arm", "armv7a-linux-androideabi26-clang"),
            Triple("x86_64", "amd64", "x86_64-linux-android26-clang"),
        )
        abis.forEach { (abi, goArch, clang) ->
            val dir = out.resolve(abi).apply { mkdirs() }
            val target = dir.resolve("libwgbridge.so")
            val proc = ProcessBuilder(
                go, "build", "-buildmode=c-shared", "-trimpath", "-buildvcs=false",
                "-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384,-soname,libwgbridge.so",
                "-o", target.absolutePath, ".",
            ).directory(wgBridgeSrc).redirectErrorStream(true).apply {
                environment().putAll(mapOf(
                    "CGO_ENABLED" to "1", "GOOS" to "android", "GOARCH" to goArch, "GOARM" to "7",
                    "CC" to bin.resolve(clang + ext).absolutePath, "GOFLAGS" to "-mod=readonly",
                ))
            }.start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("wgbridge ($abi) derlenemedi:\n$log")
            dir.resolve("libwgbridge.h").delete()
        }
    }
}

tasks.register<Exec>("runNdkBuild") {
    dependsOn(prepareNativeSources)
    group = "build"
    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(
        listOf(
            "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
            "NDK_LIBS_OUT=src/main/jniLibs",
            "APP_BUILD_SCRIPT=build/intermediates/nativeSources/Android.mk",
            "NDK_APPLICATION_MK=build/intermediates/nativeSources/Application.mk",
            "-j",
            Runtime.getRuntime().availableProcessors().toString()
        )
    )
    workingDir = projectDir
}

tasks.preBuild {
    dependsOn("runNdkBuild", buildWgBridge)
}

// ---------------------------------------------------------------------------
// Robolectric'in Android jar'ı (SDK 34) test SIRASINDA indirilmez: Robolectric'in
// kendi indiricisi yeniden denemez ve önbelleğe alınmaz; Maven Central CI'da
// istek sınırına (429) takılınca arayüz testleri koddan bağımsız olarak
// düşüyordu. Jar Gradle ile çözülür (önbellekli, yeniden denemeli) ve
// Robolectric çevrimdışı kipte bu klasörden okur. Testlerde yeni bir SDK
// kullanılırsa sürümü buraya eklenmeli.
// ---------------------------------------------------------------------------
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val prepareRobolectricDeps = tasks.register<Copy>("prepareRobolectricDeps") {
    from(robolectricRuntime)
    into(robolectricDeps)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// Release imzası kapısı — yanlışlıkla debug anahtarıyla imzalanmış bir sürümün
// dağıtılmasını engeller. assembleRelease / bundleRelease bu göreve bağlıdır.
// ---------------------------------------------------------------------------
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Release derlemesi için gerçek bir imzalama anahtarı tanımlı mı denetler."
    doFirst {
        if (hasReleaseSigning) {
            logger.lifecycle("Release imzası: RESMİ anahtar (alias=$releaseKeyAlias).")
            return@doFirst
        }
        if (allowDebugSigning) {
            logger.warn(
                "UYARI: Release DEBUG anahtarıyla imzalanıyor (allowDebugSigning=true). " +
                    "Bu APK dağıtılmamalı — imzası her makinede/CI çalıştırmasında farklıdır, " +
                    "üzerine güncelleme kurulamaz ve Play Protect uyarısı verir."
            )
            return@doFirst
        }
        throw GradleException(
            """
            Release imzalama anahtarı bulunamadı — derleme durduruldu.

            Şu dördü de tanımlı olmalı (keystore.properties dosyasında ya da ortam değişkeni olarak):
              storeFile     / RELEASE_STORE_FILE
              storePassword / RELEASE_STORE_PASSWORD
              keyAlias      / RELEASE_KEY_ALIAS
              keyPassword   / RELEASE_KEY_PASSWORD

            Yerel kurulum:  keystore.properties.example dosyasını keystore.properties olarak kopyala.
            CI kurulumu:    KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD secret'ları.
            Adım adım:      docs/Imzalama-Rehberi.md

            Sadece hızlı bir test APK'sı istiyorsan (DAĞITMA):
              ./gradlew assembleRelease -PallowDebugSigning=true
            """.trimIndent()
        )
    }
}

// preReleaseBuild: release varyantının en erken adımı — kapı, paketleme başlamadan
// önce kapanır. packageRelease* de ayrıca bağlanır (emniyet kemeri).
tasks.matching {
    it.name in setOf("preReleaseBuild", "packageRelease", "packageReleaseBundle")
}.configureEach {
    dependsOn(verifyReleaseSigning)
}
