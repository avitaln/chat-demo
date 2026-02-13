package com.chatdemo.backend.document

import com.chatdemo.common.document.FetchedDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream

class DocumentTextExtractorTest extends AnyFunSuite with Matchers {

  test("extracts text from docx") {
    val docxBytes = createDocx("Noam Nahon CV 2025")
    val extractor = new DocumentTextExtractor()

    val text = extractor.extract(FetchedDocument(
      "https://example.com/cv.docx",
      docxBytes,
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ))

    text should include("Noam Nahon CV 2025")
  }

  private def createDocx(content: String): Array[Byte] = {
    val document = new XWPFDocument()
    try {
      val out = new ByteArrayOutputStream()
      try {
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText(content)
        document.write(out)
        out.toByteArray
      } finally {
        out.close()
      }
    } finally {
      document.close()
    }
  }
}
