package org.openldes.rdfvalidator

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

  // --- regression: Fix 2 – blank-node union members in createOrList ---

  it should "not emit sh:class for blank-node members of an owl:unionOf" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Sensor a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:observes ;
          owl:someValuesFrom [
            owl:unionOf ( ex:Temperature [ a owl:Class ] )
          ]
        ] .
    """)
    val shacl         = OwlToShaclGenerator.generate(ontology)
    val shClass       = shacl.createProperty(SH + "class")
    val classValues   = shacl.listStatements(null, shClass, null).asScala.toList
    classValues.forall(_.getObject.isURIResource) shouldBe true
  }

  // --- regression: Fix 3 – duplicate sh:minCount when both owl:cardinality and owl:minCardinality are present ---

  it should "emit exactly one sh:minCount when owl:cardinality and owl:minCardinality are both present" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
      @prefix ex:   <http://example.org/> .
      ex:Observation a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:hasResult ;
          owl:minCardinality "1"^^xsd:nonNegativeInteger ;
          owl:cardinality    "1"^^xsd:nonNegativeInteger ;
        ] .
    """)
    val shacl    = OwlToShaclGenerator.generate(ontology)
    val minCount = shacl.createProperty(SH + "minCount")
    val values   = shacl.listStatements(null, minCount, null).asScala.toList
    values should have size 1
    values.head.getObject.asLiteral().getInt shouldBe 1
  }
}
