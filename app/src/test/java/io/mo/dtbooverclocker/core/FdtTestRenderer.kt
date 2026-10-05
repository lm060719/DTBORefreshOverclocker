package io.mo.dtbooverclocker.core

import java.nio.ByteBuffer

object FdtTestRenderer {
    /** Test-only lossless value rendering, using the production FDT reader on the supplied image. */
    fun render(bytes: ByteArray): String {
        class Node(val name: String) {
            val properties = linkedMapOf<String, ByteArray>()
            val children = linkedMapOf<String, Node>()
        }
        val root = Node("/")
        FdtReader.readAllProperties(bytes).forEach { (path, value) ->
            val segments = path.split('/').filter(String::isNotEmpty)
            var node = root
            segments.dropLast(1).forEach { name -> node = node.children.getOrPut(name) { Node(name) } }
            node.properties[segments.last()] = value
        }
        return buildString {
            appendLine("/dts-v1/;")
            fun emit(node: Node, indent: String) {
                appendLine("$indent${node.name} {")
                node.properties.forEach { (name, bytes) ->
                    val raw = when {
                        bytes.isEmpty() -> ""
                        bytes.last() == 0.toByte() && bytes.all { it == 0.toByte() || (it.toInt() and 255) in 32..126 } ->
                            " = " + bytes.toString(Charsets.US_ASCII).dropLast(1).split('\u0000').joinToString(", ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
                        bytes.size % 4 == 0 -> {
                            val buffer = ByteBuffer.wrap(bytes)
                            " = <" + List(bytes.size / 4) { "0x${buffer.int.toUInt().toString(16)}" }.joinToString(" ") + ">"
                        }
                        else -> " = [" + bytes.joinToString(" ") { "%02x".format(it) } + "]"
                    }
                    appendLine("$indent    $name$raw;")
                }
                node.children.values.forEach { emit(it, "$indent    ") }
                appendLine("$indent};")
            }
            emit(root, "")
        }
    }
}
