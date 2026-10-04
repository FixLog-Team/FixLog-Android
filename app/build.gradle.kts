import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 릴리스 서명 설정. 루트의 keystore.properties 가 있으면 그 값으로 release 를 서명한다.
// keystore.properties 와 *.jks/*.keystore 는 .gitignore 로 제외되어 저장소에 커밋되지 않는다.
// keystore.properties 예시:
//   storeFile=release.jks
//   storePassword=****
//   keyAlias=fixlog
//   keyPassword=****
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}
val hasReleaseKeystore: Boolean = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "kr.co.fixlog"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.fixlog"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // keystore.properties 가 있을 때만 release 서명 설정을 구성한다(없으면 생성하지 않음).
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 코드 축소·난독화 + 미사용 리소스 제거. Moshi/JS 브리지 등 리플렉션 대상은 proguard-rules.pro에서 keep.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // keystore.properties 가 있으면 업로드 키로 서명, 없으면(로컬 테스트) 서명 미지정.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        // 런타임에서 BuildConfig.DEBUG로 로깅/웹뷰 디버깅을 가드하기 위해 활성화.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}