package io.mo.dtbooverclocker.model

enum class AppLanguage(val code: String) {
    FOLLOW_SYSTEM("system"),
    ENGLISH("en"),
    CHINESE("zh");

    companion object {
        fun fromCode(code: String?): AppLanguage = when (code) {
            "en" -> ENGLISH
            "zh" -> CHINESE
            else -> FOLLOW_SYSTEM
        }
    }
}
