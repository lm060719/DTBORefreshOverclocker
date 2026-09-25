package io.mo.dtbooverclocker.core.devicetree

/**
 * 节点名与属性名的唯一校验规则，编辑器与 UI 共用，避免两处规则漂移。
 *
 * 依据 Devicetree 规范：节点名为 `name[@unit-address]`，字符集 `[A-Za-z0-9,._+-]`（`#` 只允许出现在属性名中）；
 * 属性名字符集 `[A-Za-z0-9,._+?#-]`。规范建议的 31 字符上限不强制，厂商 DTS 中常见更长的面板节点名。
 */
object DeviceTreeNames
{
    private val nodeNameRegex = Regex("^[A-Za-z0-9,._+-]+(@[A-Za-z0-9,._+-]+)?$")
    private val propertyNameRegex = Regex("^[A-Za-z0-9,._+?#-]+$")

    fun nodeNameError(name: String): String?
    {
        return when
        {
            name.isBlank() -> "节点名不能为空"
            name.count { it == '@' } > 1 -> "节点名只能包含一个 @"
            name.startsWith('@') -> "@ 前必须有节点名"
            name.endsWith('@') -> "@ 后必须有 unit-address"
            !nodeNameRegex.matches(name) -> "节点名只能包含字母、数字和 , . _ + -（以及一个 @unit-address）"
            else -> null
        }
    }

    fun propertyNameError(name: String): String?
    {
        return when
        {
            name.isBlank() -> "属性名不能为空"
            !propertyNameRegex.matches(name) -> "属性名只能包含字母、数字和 , . _ + ? # -"
            else -> null
        }
    }
}
