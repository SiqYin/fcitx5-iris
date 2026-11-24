plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "io.github.siqyin.iris"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification",
                    // fcitx5-rime addon (merged from :plugin:rime, single-APK build)
                    "rime"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // 本发版只保留两个输入法：Rime（默认）与 English（androidkeyboard 动态提供）。
    // 以下全部不进包：
    //  1) 码表类 addon 的 conf：cangjie/erbi/qxm/wanfeng/pinyin/shuangpin/table
    //  2) 具体码表方案：zrm 自然码、wbx 五笔字型、wbpy 五笔拼音、db 电报码
    //  3) 上述方案各自的 .main.dict（usr/share/fcitx5/table/，合计 ~8 MB 压缩）
    //  4) libime 语言模型与词库（usr/share/libime/，~34 MB 压缩）：
    //     zh_CN.lm 只有拼音 addon 使用，而 pinyin.conf 已在上面排除，属纯死重。
    //     比对：Rime 走自己的 librime + rime-data，完全不经 libime。
    excludeFiles = listOf(
        "cangjie", "erbi", "qxm", "wanfeng", "pinyin", "shuangpin", "table",
        "zrm", "wbx", "wbpy", "db"
    ).map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    } + listOf(
        "usr/share/fcitx5/table/zrm.main.dict",
        "usr/share/fcitx5/table/wbx.main.dict",
        "usr/share/fcitx5/table/wbpy.main.dict",
        "usr/share/fcitx5/table/db.main.dict",
        "usr/share/libime/extb.dict",
        "usr/share/libime/sc.dict",
        "usr/share/libime/zh_CN.lm",
        "usr/share/libime/zh_CN.lm.predict"
    )
    installPrebuiltAssets = true
}

generateDataDescriptor {
    // rime-data 下的 opencc 目录用软链指向主 opencc 目录（原 :plugin:rime 提供，现随本体打包）
    symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
