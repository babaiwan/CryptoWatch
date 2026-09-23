package com.crypto.cryptowatch.ui

import com.crypto.cryptowatch.settings.CryptoSettings
import com.intellij.openapi.application.ApplicationManager
import java.util.Locale

/**
 * 界面语言。
 *
 * 之所以不直接用平台自带的 `DynamicBundle`：那套机制要求所有文案都带上 `bundleKey`
 * 并依赖平台的语言包机制，而本插件需要兼容 2020.3，且要允许用户**在插件里单独切换语言**
 * （不跟随 IDE）。这里自建一层极薄的封装，行为在所有版本上一致。
 */
enum class Language {
    /** 跟随 IDE / JVM 的语言环境。 */
    SYSTEM,
    EN,
    ZH;

    /** 落盘时保存的值。 */
    val code: String
        get() = when (this) {
            SYSTEM -> "system"
            EN -> "en"
            ZH -> "zh"
        }

    /** 语言自身的名称（用该语言书写），保证任何语言环境下都能被用户认出来。 */
    fun displayName(): String = when (this) {
        SYSTEM -> I18n.text("settings.language.system")
        EN -> I18n.text("lang.display.en")
        ZH -> I18n.text("lang.display.zh")
    }

    fun toLocale(): Locale = when (this) {
        SYSTEM -> Locale.getDefault()
        EN -> Locale.ENGLISH
        ZH -> Locale.SIMPLIFIED_CHINESE
    }

    companion object {
        /**
         * 从持久化值还原。
         *
         * 刻意使用 `values()` 而不是 `entries`：`Enum.entries` 依赖 Kotlin 1.9 的
         * `kotlin.enums.EnumEntries`，在 2020.3（内置 Kotlin 1.4）上会抛 `NoSuchMethodError`，
         * 且这段代码位于枚举的 `<clinit>` 中，会导致整个枚举类初始化失败。
         */
        fun fromPersisted(raw: String?): Language =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * 文案访问入口。
 *
 * - 文案表见 [Strings]（纯 Kotlin 常量，**不是** `.properties`——原因见该文件的注释：
 *   `.properties` 的写入链路会把中文替换成 `?`）；
 * - 当前语言取自 [CryptoSettings]（用户可在设置页切换，默认跟随 IDE）；
 * - 缺失的键先回退到英文，再回退到键名本身，因此界面上永远不会出现空白；
 * - 切换语言后由 [com.crypto.cryptowatch.settings.SettingsNotifier] 广播刷新，
 *   所有界面文案都会重新取值，无需重启 IDE。
 */
object I18n {

    /** 当前生效的界面语言。设置未初始化时回退到跟随 IDE。 */
    fun language(): Language = runCatching {
        Language.fromPersisted(CryptoSettings.getInstance().language)
    }.getOrDefault(Language.SYSTEM)

    /** 取文案（不做参数替换）。 */
    fun text(key: String): String = table(language())[key] ?: Strings.EN[key] ?: key

    /**
     * 取文案并替换 `{0}` / `{1}` 形式的占位符。
     *
     * 这里**刻意不用 [java.text.MessageFormat]**：`MessageFormat` 会把单引号当转义符，
     * 中文文案里的标点与英文里的 "don't" 都会引发难以排查的解析异常或丢字。
     */
    fun text(key: String, vararg args: Any?): String {
        var result = text(key)
        args.forEachIndexed { index, arg ->
            result = result.replace("{$index}", arg?.toString() ?: "")
        }
        return result
    }

    /**
     * 在 EDT 上把「文案变化」广播给所有已打开的工具窗口。
     *
     * 语言切换与主题切换一样，属于「已经创建的组件需要按新配置重画一遍」的场景，
     * 因此复用同一套广播通道。
     */
    fun notifyLanguageChanged() {
        ApplicationManager.getApplication().invokeLater {
            com.crypto.cryptowatch.settings.SettingsNotifier.fireLanguageChanged()
        }
    }

    /**
     * 取某个语言对应的文案表。
     *
     * [Language.SYSTEM] 时按 JVM 默认语言选择：中文环境用中文，其余一律用英文
     * （英文是 [Strings.EN]，同时充当缺失键的兜底）。
     */
    private fun table(language: Language): Map<String, String> = when (language) {
        Language.ZH -> Strings.ZH
        Language.EN -> Strings.EN
        Language.SYSTEM -> {
            val default = Locale.getDefault().language
            if (default.equals("zh", ignoreCase = true)) Strings.ZH else Strings.EN
        }
    }
}
