package io.mo.dtbooverclocker.core.devicetree

/** Resolves which base-tree node each overlay fragment applies to. */
object OverlayTargets
{
    /** `__overlay__` path -> label, from `target = <0xffffffff>` relocations recorded in `__fixups__`.
     * DTC emits string lists as separate strings, as a single string with embedded NULs,
     * or, for long lists, as a byte array `[ 2f 66 ... 00 ]`.
     */
    fun fixupLabels(document: DeviceTreeDocument): Map<String, String>
    {
        val labels = mutableMapOf<String, MutableSet<String>>()
        document.findNode("/__fixups__")?.properties?.forEach { property ->
            descriptors(property.rawValue.orEmpty()).forEach descriptorLoop@{ descriptor ->
                val match = Regex("^(/[^:]+):target:0$").matchEntire(descriptor) ?: return@descriptorLoop
                val fragment = document.findNode(match.groupValues[1]) ?: return@descriptorLoop
                if (fragment.properties.count { it.name == "target" } != 1) return@descriptorLoop
                val raw = fragment.properties.first { it.name == "target" }.rawValue
                if (DtsNumericValueCodec.decodeU32(raw) != 0xffffffffL) return@descriptorLoop
                val path = "${fragment.path}/__overlay__"
                if (document.findNode(path) != null) labels.getOrPut(path) { mutableSetOf() }.add(property.name)
            }
        }
        return labels.mapNotNull { (path, targets) -> targets.singleOrNull()?.let { path to it } }.toMap()
    }

    private fun descriptors(raw: String): Sequence<String>
    {
        val bytes = Regex("^\\s*\\[([0-9a-fA-F\\s]*)]\\s*$").matchEntire(raw)?.groupValues?.get(1)
        if (bytes != null)
        {
            val text = bytes.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                .map { it.toInt(16).toChar() }.joinToString("")
            return text.split('\u0000').asSequence()
        }
        return Regex("\"([^\"]*)\"").findAll(raw).flatMap { match ->
            match.groupValues[1].replace(Regex("\\\\(?:x00|0{1,3})"), "\u0000").split('\u0000').asSequence()
        }
    }

    /** A stable identity for the target of [overlayPath], or null when it cannot be determined. */
    fun target(document: DeviceTreeDocument, overlayPath: String, fixupLabels: Map<String, String>): String?
    {
        fixupLabels[overlayPath]?.let { return "&$it" }
        val fragment = document.findNode(overlayPath.substringBeforeLast('/')) ?: return null
        fragment.properties.singleOrNull { it.name == "target-path" }?.rawValue?.let { return "path:$it" }
        val raw = fragment.properties.singleOrNull { it.name == "target" }?.rawValue ?: return null
        // An unresolved relocation without a fixup entry carries no identity.
        return if (DtsNumericValueCodec.decodeU32(raw) == 0xffffffffL) null else "phandle:$raw"
    }
}
