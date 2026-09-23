import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.crypto"
// 版本号必须递增：IDE 会拒绝安装与已装版本号相同的包
// （报错 "plugin already contains version X in channel"）。
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 编译期平台。目标是最低支持 2020.3（build 203），因此代码里刻意只使用
        // 2020.3 就已存在的 API（例如用 Configurable 而非 Kotlin UI DSL、
        // 用 ContentManager.factory 而非 ContentFactory.getInstance()），
        // 同时把字节码降到 Java 11，见文件末尾的 jvmTarget / release 配置。
        create("IC", "2025.1.4.1")
        testFramework(TestFrameworkType.Platform)
    }

    // 2020.3 的 Kotlin 标准库位于 Kotlin 插件的 lib 目录，不在平台 lib 目录里，
    // 因此必须显式声明。用 compileOnly 而不是 implementation：运行时平台早已提供了
    // Kotlin 标准库（由插件的父 ClassLoader 负责加载），把 stdlib 打进 jar 只会造成重复。
    compileOnly(kotlin("stdlib"))

    // The plugin intentionally has ZERO third-party runtime dependencies:
    // HTTP uses JDK HttpClient and JSON parsing is implemented in-house.
}

intellijPlatform {
    /**
     * 兼容性护栏。
     *
     * 之前的兼容问题（枚举 entries、服务注解注册、平台静态入口）都无法在
     * 「用新平台编译」时暴露，只有针对**最低支持版本**做校验才能发现。
     * 因此这里显式指定 2020.3 作为校验目标，配合下面的 verifyPlugin 任务，
     * 一旦产物里再出现老平台不存在的方法/字段，构建阶段就会报出来。
     */
    pluginVerification {
        ides {
            create("IC", "2020.3")
            recommended()
        }
    }

    pluginConfiguration {
        id = "com.crypto.CryptoWatch"
        name = "CryptoWatch"
        ideaVersion {
            // 203 = IntelliJ Platform 2020.3，对应用户报错里提到的旧版本
            sinceBuild = "203"
            untilBuild = provider { null }
        }

        changeNotes = """
            <ul>
                <li>1.0.2 - 移除插件图标（此前误用了 SDK 默认模板图标），改用平台默认图标.</li>
                <li>1.0.1 - 修复自选不更新: 手动加入的币种（如 ZEC）会立即出现在列表中；
                    修复实时推送数虚高（只增不减的缓存），刷新时严格按当前自选重新订阅；
                    界面精简: 去掉市值与来源、去掉「列表更新于」，信息框压缩为单行.</li>
                <li>1.0.0 - 实时行情看盘: 多数据源互备、自选、合约、走势图与 K 线.</li>
            </ul>
        """.trimIndent()
    }
}

// 构建工具链仍是 JDK 21（Gradle 与 IntelliJ Platform Gradle Plugin 2.x 的运行要求），
// 但产物字节码被刻意降到 Java 11：2020.3 跑在 JBR 11 上，
// 若产出 17/21 字节码，插件会在老 IDE 上直接加载失败。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)

        // 关键：把语言/API 版本压到 1.8。
        //
        // Kotlin 1.9 起，编译器会为「每一个」Kotlin 枚举自动生成 `entries` 属性，
        // 其实现引用了 `kotlin.enums.EnumEntriesKt` —— 该类是 Kotlin 1.9 才新增的。
        // 也就是说，即源码里从未写过 `entries`，产物的每个枚举类也会带上这段初始化逻辑。
        // 老 IDE（如 2020.3）内置的是 Kotlin 1.4 标准库，没有这个类，于是枚举一被加载
        // 就抛 `NoClassDefFoundError: kotlin/enums/EnumEntriesKt`，MarketCategory /
        // BinanceWsClient.ConnectionState 等全部初始化失败，插件自然「什么都不显示」。
        //
        // 降到 1.8 后编译器不再生成 `entries`，枚举只保留 values()/valueOf()，
        // 与老平台完全兼容。源码中因此也不能再使用 `Enum.entries`。
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    }
}
