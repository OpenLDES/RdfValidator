package org.openldes.rdfvalidator

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File

class NigellaFrameExportSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers {

  private val outDir      = new File("target/generated-inferred")
  private val frameFile   = new File(outDir, "Nigella-Lawson-Brownies-frame.json")
  private val framedFile  = new File(outDir, "Nigella-Lawson-Brownies-framed.jsonld")
  private val nigellaFile = new File(
    getClass.getClassLoader.getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)

  override def beforeAll(): Unit = {
    val model = RdfUtils.parseTurtle(nigellaFile)
    val frame  = RdfUtils.deriveFrame(model)
    val jsonLd = RdfUtils.modelToJsonLd(model).getOrElse(
      fail("modelToJsonLd returned None for non-empty Nigella model"))
    val framed = RdfUtils.frameJsonLd(jsonLd, frame).getOrElse(
      fail("frameJsonLd returned None"))

    outDir.mkdirs()
    RdfUtils.mapper.writerWithDefaultPrettyPrinter().writeValue(frameFile, frame)
    RdfUtils.mapper.writerWithDefaultPrettyPrinter().writeValue(framedFile, framed)
  }

  "NigellaFrameExport" should "write the derived frame JSON to target/generated-inferred/" in {
    frameFile.exists() shouldBe true
    frameFile.length() should be > 0L
  }

  it should "write the framed JSON-LD to target/generated-inferred/" in {
    framedFile.exists() shouldBe true
    framedFile.length() should be > 0L
  }

  it should "produce a @graph with at least one record in the framed output" in {
    val framed = RdfUtils.mapper.readTree(framedFile)
    val graph  = RdfUtils.extractGraph(framed)
    graph should not be empty
    graph.get.size() should be > 0
  }

  it should "roundtrip: framed JSON-LD parsed back into a model is isomorphic with the original Turtle model" in {
    val expected = RdfUtils.parseTurtle(nigellaFile)
    val actual   = RdfUtils.parseJsonLd(framedFile)
    actual.isIsomorphicWith(expected) shouldBe true
  }
}
