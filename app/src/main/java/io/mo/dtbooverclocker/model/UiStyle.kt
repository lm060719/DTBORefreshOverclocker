package io.mo.dtbooverclocker.model

enum class UiStyle(val code: String, val label: String) {
    MATERIAL("material", "Material 3"),
    MIUIX("miuix", "Miuix");

    companion object {
        fun fromCode(code: String?): UiStyle = entries.firstOrNull { it.code == code } ?: MATERIAL
    }
}
