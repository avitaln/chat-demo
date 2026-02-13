package com.chatdemo.backend.document

import com.chatdemo.common.document.FetchedDocument
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument

import java.io.{ByteArrayInputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Converts fetched document bytes to plain text.
 */
class DocumentTextExtractor {

  @throws[IOException]
  def extract(document: FetchedDocument): String = {
    if (document == null || document.bytes == null || document.bytes.length == 0) {
      return ""
    }

    val sourceUrl = if (document.sourceUrl == null) "" else document.sourceUrl.toLowerCase(Locale.ROOT)
    val contentType = if (document.contentType == null) "" else document.contentType.toLowerCase(Locale.ROOT)

    if (contentType.contains("pdf") || sourceUrl.endsWith(".pdf")) {
      return extractPdf(document.bytes)
    }

    if (contentType.contains("wordprocessingml") ||
      sourceUrl.endsWith(".docx") ||
      sourceUrl.endsWith(".docm")) {
      return extractDocx(document.bytes)
    }

    new String(document.bytes, StandardCharsets.UTF_8)
  }

  private def extractPdf(bytes: Array[Byte]): String = {
    val pdf = Loader.loadPDF(bytes)
    try {
      val stripper = new PDFTextStripper()
      stripper.getText(pdf)
    } finally {
      pdf.close()
    }
  }

  private def extractDocx(bytes: Array[Byte]): String = {
    val input = new ByteArrayInputStream(bytes)
    val doc = new XWPFDocument(input)
    try {
      val extractor = new XWPFWordExtractor(doc)
      try {
        extractor.getText
      } finally {
        extractor.close()
      }
    } finally {
      doc.close()
    }
  }
}
