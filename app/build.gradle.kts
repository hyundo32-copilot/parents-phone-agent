import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 비밀값은 local.properties(gitignore)에서만 읽는다. 없으면 빈 문자열.
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String =
    (secrets.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.hyundo.parentsagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hyundo.parentsagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-stage1"

        buildConfigField("String", "LINER_API_KEY", "\"${secret("liner.api.key")}\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${secret("telegram.bot.token")}\"")
        buildConfigField("String", "TELEGRAM_CHAT_ID", "\"${secret("telegram.chat.id")}\"")
    }

    // debug 서명 키 고정: 기본 debug keystore는 VM 임시 영역(/root)에 생겨 VM 교체 때 날아가고, 키가 바뀌면 기존 설치 앱 위에 업데이트 설치가 안 된다. 프로젝트 내 debug.keystore(홈에 남아 VM 교체에도 유지, .gitignore로 커밋 제외)를 쓴다.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 단위 테스트(JVM)에는 안드로이드 org.json 스텁이 동작하지 않아 실구현을 쓴다.
    testImplementation("org.json:json:20240303")
}
