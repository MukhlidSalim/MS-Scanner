package com.example.engine.pdf

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free Word (.docx / OOXML) writer: one paragraph per text line, XML-escaped,
 * right-to-left paragraphs for Arabic lines. Replaces the former "HTML saved as .doc" export.
 */
object WordDocumentWriter {
    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").filter { it == '\t' || it >= ' ' }

    private fun isArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }

    private fun paragraph(text: String, heading: Boolean = false): String {
        val rtl = isArabic(text)
        val pPr = buildString {
            append("<w:pPr>")
            if (rtl) append("<w:bidi/>")
            if (heading) append("<w:jc w:val=\"center\"/>")
            append("</w:pPr>")
        }
        val rPr = buildString {
            append("<w:rPr>")
            if (heading) append("<w:b/><w:sz w:val=\"32\"/>")
            if (rtl) append("<w:rtl/>")
            append("</w:rPr>")
        }
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"
    }

    fun write(out: File, title: String, body: String) {
        val paras = StringBuilder()
        if (title.isNotBlank()) paras.append(paragraph(title, heading = true))
        body.lineSequence().forEach { paras.append(paragraph(it)) }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paras<w:sectPr/></w:body></w:document>"""
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/document.xml", document)
        }
    }
}
