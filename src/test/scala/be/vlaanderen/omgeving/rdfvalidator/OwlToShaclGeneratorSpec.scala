package be.vlaanderen.omgeving.rdfvalidator

import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.{Lang, RDFParser}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class OwlToShaclGeneratorSpec extends AnyFlatSpec with Matchers {

  private val SH = OwlToShaclGenerator.SH

  private def parseTTL(ttl: String) = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  // --- generate ---

  "generate" should "return an empty model when the ontology has no OWL classes" in {
    val shacl = OwlToShaclGenerator.generate(ModelFactory.createDefaultModel())
    shacl.isEmpty shouldBe true
  }

  it should "create a NodeShape with sh:targetClass for each OWL class" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:Sensor a owl:Class .
    """)
    val shacl       = OwlToShaclGenerator.generate(ontology)
    val targetClass = shacl.createProperty(SH + "targetClass")
    val sensor      = shacl.createResource("http://example.org/Sensor")
    shacl.contains(null, targetClass, sensor) shouldBe true
  }

  it should "add sh:minCount 1 for an owl:someValuesFrom restriction" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Sensor a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:observes ;
          owl:someValuesFrom ex:Property ;
        ] .
    """)
    val shacl    = OwlToShaclGenerator.generate(ontology)
    val minCount = shacl.createProperty(SH + "minCount")
    val values   = shacl.listStatements(null, minCount, null).asScala
      .map(_.getObject.asLiteral().getInt).toList
    values should contain(1)
  }

  it should "add both sh:minCount and sh:maxCount for an owl:cardinality restriction" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Observation a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:hasResult ;
          owl:cardinality 1 ;
        ] .
    """)
    val shacl    = OwlToShaclGenerator.generate(ontology)
    val minCount = shacl.createProperty(SH + "minCount")
    val maxCount = shacl.createProperty(SH + "maxCount")
    shacl.contains(null, minCount, null) shouldBe true
    shacl.contains(null, maxCount, null) shouldBe true
  }

  it should "add sh:class but no sh:minCount for an owl:allValuesFrom restriction" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Observation a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:hasFeatureOfInterest ;
          owl:allValuesFrom ex:FeatureOfInterest ;
        ] .
    """)
    val shacl    = OwlToShaclGenerator.generate(ontology)
    val shClass  = shacl.createProperty(SH + "class")
    val minCount = shacl.createProperty(SH + "minCount")
    shacl.contains(null, shClass, null) shouldBe true
    shacl.contains(null, minCount, null) shouldBe false
  }
}
