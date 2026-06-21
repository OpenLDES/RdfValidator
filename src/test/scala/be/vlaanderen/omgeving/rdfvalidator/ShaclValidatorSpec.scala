package be.vlaanderen.omgeving.rdfvalidator

import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.shacl.Shapes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShaclValidatorSpec extends AnyFlatSpec with Matchers {

  private def parseTTL(ttl: String) = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  private val personShapeTTL =
    """
      |@prefix sh:  <http://www.w3.org/ns/shacl#> .
      |@prefix ex:  <http://example.org/> .
      |ex:PersonShape
      |  a sh:NodeShape ;
      |  sh:targetClass ex:Person ;
      |  sh:property [
      |    sh:path    ex:name ;
      |    sh:minCount 1 ;
      |    sh:message "A person must have a name." ;
      |  ] .
    """.stripMargin

  private def personShapes: Shapes = Shapes.parse(parseTTL(personShapeTTL))

  // --- loadShapes ---

  "loadShapes" should "parse a SHACL file from the classpath" in {
    val url    = getClass.getClassLoader.getResource("ontologies/shacl.ttl").toString
    val shapes = ShaclValidator.loadShapes(url)
    shapes should not be null
  }

  // --- validate ---

  "validate" should "return a conforming report for data that satisfies all constraints" in {
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:name "Alice" .
    """)
    val report = ShaclValidator.validate(data, personShapes)
    report.conforms() shouldBe true
  }

  it should "return a non-conforming report when a required property is missing" in {
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Bob a ex:Person .
    """)
    val report = ShaclValidator.validate(data, personShapes)
    report.conforms() shouldBe false
  }

  it should "report a violation for each offending node" in {
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Bob   a ex:Person .
      ex:Carol a ex:Person .
    """)
    val report = ShaclValidator.validate(data, personShapes)
    report.conforms() shouldBe false
    report.getEntries.size() shouldBe 2
  }

  // --- printReport ---

  "printReport" should "not throw for a conforming report" in {
    val data   = parseTTL("""@prefix ex: <http://example.org/> . ex:Alice a ex:Person ; ex:name "Alice" .""")
    val report = ShaclValidator.validate(data, personShapes)
    noException should be thrownBy ShaclValidator.printReport(report)
  }

  it should "not throw for a non-conforming report" in {
    val data   = parseTTL("""@prefix ex: <http://example.org/> . ex:Bob a ex:Person .""")
    val report = ShaclValidator.validate(data, personShapes)
    noException should be thrownBy ShaclValidator.printReport(report)
  }
}
