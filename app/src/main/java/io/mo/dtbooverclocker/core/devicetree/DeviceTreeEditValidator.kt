package io.mo.dtbooverclocker.core.devicetree

/** Conservative guard for free-form structural edits; never rewrites guessed references. */
object DeviceTreeEditValidator {
    // Hoisted: validate() visits every property of the document, so per-call construction adds up.
    private val quotedStringRegex = Regex("\"([^\"\\\\]*)\"")
    private val cellSeparatorRegex = Regex("[\\s,]+")
    private val phandlePropertyNames = setOf("phandle", "linux,phandle")

    fun validate(document: DeviceTreeDocument, change: DeviceTreeChange) {
        if (change !is DeleteNodeChange && change !is RenameNodeChange) return
        val deleting = change is DeleteNodeChange
        val path = change.nodePath
        fun inside(value: String) = value == path || value.startsWith("$path/")
        val nodes = document.flatten()
        val affected = linkedSetOf<String>()
        val index = DeviceTreeReferenceIndexer.build(document)
        index.references.forEach { ref ->
            if (ref.targetNodePath?.let(::inside) == true &&
                (!deleting || !inside(ref.sourceNodePath)) &&
                (deleting || ref.kind == DeviceTreeReferenceKind.PATH)) {
                affected += "${ref.sourceNodePath}/${ref.propertyName}"
            }
        }
        val removedPhandles = if (deleting) index.phandles.filterValues(::inside).keys else emptySet()
        nodes.forEach { node ->
            if (deleting && inside(node.path)) return@forEach
            // __local_fixups__ mirrors the referenced subtree's path.
            if (node.path.startsWith("/__local_fixups__/") &&
                inside(node.path.removePrefix("/__local_fixups__"))) {
                affected += node.path
            }
            node.properties.forEach { property ->
                val raw = property.rawValue.orEmpty()
                // Includes __symbols__, __fixups__, aliases and chosen path strings.
                quotedStringRegex.findAll(raw).forEach { match ->
                    val target = match.groupValues[1].substringBefore(':')
                    if (inside(target)) affected += "${node.path}/${property.name}"
                }
                if (removedPhandles.isNotEmpty() && property.name !in phandlePropertyNames &&
                    raw.startsWith('<') && raw.endsWith('>')) {
                    val cells = raw.drop(1).dropLast(1).split(cellSeparatorRegex)
                    if (cells.any { token ->
                        val number = if (token.startsWith("0x", true)) token.drop(2).toLongOrNull(16) else token.toLongOrNull()
                        number in removedPhandles
                    }) affected += "${node.path}/${property.name}（数值 phandle 候选）"
                }
            }
        }
        require(affected.isEmpty()) {
            "${if (deleting) "删除" else "重命名"} $path 可能使引用失效，请先处理以下引用或元数据：" +
                affected.take(8).joinToString("、") + if (affected.size > 8) " 等 ${affected.size} 项" else ""
        }
    }
}
