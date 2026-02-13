lazy val root = (project in file("."))
  .settings(
    name := "chat-demo",
    version := "1.0-SNAPSHOT",
    organization := "com.chatdemo",
    scalaVersion := "3.3.4",

    libraryDependencies ++= Seq(
      // LangChain4j Core
      "dev.langchain4j" % "langchain4j" % "1.11.0",
      // LangChain4j OpenAI (for ChatGPT and Grok)
      "dev.langchain4j" % "langchain4j-open-ai" % "1.11.0",
      // LangChain4j Anthropic (for Claude)
      "dev.langchain4j" % "langchain4j-anthropic" % "1.11.0",
      // LangChain4j Google AI Gemini
      "dev.langchain4j" % "langchain4j-google-ai-gemini" % "1.11.0",
      // Google Cloud Storage client for image uploads
      "com.google.cloud" % "google-cloud-storage" % "2.62.1",
      // Jackson for JSON persistence
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.17.0",
      // PDF parsing for document context extraction
      "org.apache.pdfbox" % "pdfbox" % "3.0.3",
      // DOCX parsing for document context extraction
      "org.apache.poi" % "poi-ooxml" % "5.2.5",
      // Log4j backend to silence StatusLogger warning
      "org.apache.logging.log4j" % "log4j-core" % "2.21.1",
      // Undertow HTTP server
      "io.undertow" % "undertow-core" % "2.3.21.Final",
      // SQLite JDBC driver
      "org.xerial" % "sqlite-jdbc" % "3.47.1.0",
      // Test dependencies
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
    ),
    Compile / mainClass := Some("com.chatdemo.app.AppMain"),

    // Fork a separate JVM for run/runMain so that sbt's own JLine
    // input handling does not interfere with Scanner(System.in).
    run / fork := true,
    run / connectInput := true
  )
