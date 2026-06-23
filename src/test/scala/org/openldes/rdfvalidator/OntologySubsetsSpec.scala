package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.vocabulary.{OWL, RDF, RDFS}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class OntologySubsetsSpec extends AnyFlatSpec with Matchers {

  private def parseTTL(ttl: String) = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  // --- extractStructuralSubset ---

  "extractStructuralSubset" should "include rdfs:subClassOf statements between named resources" in {
    val ontology = parseTTL("""
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Sensor rdfs:subClassOf ex:Device .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, RDFS.subClassOf, null) shouldBe true
  }

  it should "include rdfs:domain and rdfs:range statements" in {
    val ontology = parseTTL("""
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:measures rdfs:domain ex:Sensor ; rdfs:range ex:Property .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, RDFS.domain, null) shouldBe true
    subset.contains(null, RDFS.range, null)  shouldBe true
  }

  it should "exclude owl:disjointWith statements" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Sensor owl:disjointWith ex:Actuator .
      ex:Sensor rdfs:subClassOf ex:Device .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, OWL.disjointWith, null) shouldBe false
    subset.contains(null, RDFS.subClassOf, null)  shouldBe true
  }

  it should "exclude subClassOf statements whose object is an anonymous node" in {
    val ontology = parseTTL("""
      @prefix owl:  <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:Sensor rdfs:subClassOf ex:Device .
      ex:Sensor rdfs:subClassOf [
        a owl:Restriction ;
        owl:onProperty ex:observes ;
      ] .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    val stmts  = subset.listStatements(null, RDFS.subClassOf, null).asScala.toList
    stmts.size shouldBe 1
    stmts.head.getObject.asResource().getURI shouldBe "http://example.org/Device"
  }

  it should "include owl:equivalentClass statements between named resources" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:Person owl:equivalentClass ex:Human .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, OWL.equivalentClass, null) shouldBe true
  }

  it should "exclude owl:equivalentClass statements whose object is a blank node" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:Foo owl:equivalentClass [ owl:onProperty ex:p ; owl:someValuesFrom ex:Bar ] .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, OWL.equivalentClass, null) shouldBe false
  }

  it should "include owl:equivalentProperty statements between named resources" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:maker owl:equivalentProperty ex:creator .
    """)
    val subset = OntologySubsets.extractStructuralSubset(ontology)
    subset.contains(null, OWL.equivalentProperty, null) shouldBe true
  }

  it should "include rdf:type owl:TransitiveProperty for named properties" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:before a owl:TransitiveProperty .
    """)
    val subset   = OntologySubsets.extractStructuralSubset(ontology)
    val before   = subset.createResource("http://example.org/before")
    subset.contains(before, RDF.`type`, OWL.TransitiveProperty) shouldBe true
  }

  it should "include rdf:type owl:SymmetricProperty for named properties" in {
    val ontology = parseTTL("""
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix ex:  <http://example.org/> .
      ex:related a owl:SymmetricProperty .
    """)
    val subset  = OntologySubsets.extractStructuralSubset(ontology)
    val related = subset.createResource("http://example.org/related")
    subset.contains(related, RDF.`type`, OWL.SymmetricProperty) shouldBe true
  }

}
