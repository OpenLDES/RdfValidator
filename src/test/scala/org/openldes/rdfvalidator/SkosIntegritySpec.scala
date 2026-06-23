package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.{Lang, RDFDataMgr, RDFParser}
import org.apache.jena.shacl.{Shapes, ValidationReport}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SkosIntegritySpec extends AnyFlatSpec with Matchers {

  private def loadClasspath(path: String): Model = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create()
      .source(getClass.getClassLoader.getResourceAsStream(path))
      .lang(Lang.TTL)
      .parse(m)
    m
  }

  private def parseTTL(ttl: String): Model = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  private lazy val constraints: Model = loadClasspath("ontologies/skos-constraints.ttl")

  private lazy val shapes: Shapes = {
    val s14Url = getClass.getClassLoader.getResource("shacl/skos-s14.ttl").toString
    Shapes.parse(RDFDataMgr.loadModel(s14Url))
  }

  private def checkDisjoint(ttl: String): ValidationResult =
    RdfUtils.checkPropertyDisjointness(parseTTL(ttl), constraints)

  private val SKOS = "http://www.w3.org/2004/02/skos/core#"

  // ── S14: prefLabel uniqueness per language (SHACL) ────────────────────────

  "S14" should "accept a concept with one prefLabel per language" in {
    val report: ValidationReport = ShaclValidator.validate(
      parseTTL(
        s"""@prefix skos: <$SKOS> .
           |@prefix ex: <http://example.org/> .
           |ex:A a skos:Concept ; skos:prefLabel "dog"@en ; skos:prefLabel "hond"@nl .""".stripMargin
      ),
      shapes
    )
    report.conforms() shouldBe true
  }

  it should "reject a concept with two prefLabels in the same language" in {
    val report: ValidationReport = ShaclValidator.validate(
      parseTTL(
        s"""@prefix skos: <$SKOS> .
           |@prefix ex: <http://example.org/> .
           |ex:A a skos:Concept ; skos:prefLabel "dog"@en ; skos:prefLabel "hound"@en .""".stripMargin
      ),
      shapes
    )
    report.conforms() shouldBe false
  }

  // ── S13: label properties are pairwise disjoint (OWL reasoning) ───────────

  "S13" should "reject a concept whose prefLabel equals its altLabel" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:prefLabel "dog"@nl ;
         |     skos:altLabel  "dog"@nl .""".stripMargin
    ).valid shouldBe false
  }

  it should "reject a concept whose prefLabel equals its hiddenLabel" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:prefLabel   "dog"@en ;
         |     skos:hiddenLabel "dog"@en .""".stripMargin
    ).valid shouldBe false
  }

  it should "reject a concept whose altLabel equals its hiddenLabel" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:altLabel    "dog"@en ;
         |     skos:hiddenLabel "dog"@en .""".stripMargin
    ).valid shouldBe false
  }

  it should "accept a concept whose prefLabel and altLabel differ" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:prefLabel "dog"@en ;
         |     skos:altLabel  "hound"@en .""".stripMargin
    ).valid shouldBe true
  }

  // ── S27: skos:related disjoint with skos:broader / skos:narrower ──────────

  "S27" should "reject a concept that is both skos:related and skos:broader to the same concept" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:related ex:B .
         |ex:A skos:broader ex:B .""".stripMargin
    ).valid shouldBe false
  }

  it should "reject a concept that is both skos:related and skos:narrower to the same concept" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:related ex:B .
         |ex:A skos:narrower ex:B .""".stripMargin
    ).valid shouldBe false
  }

  it should "accept a concept with only skos:related (no broader/narrower to same target)" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:related ex:B .
         |ex:A skos:broader ex:C .""".stripMargin
    ).valid shouldBe true
  }

  // ── S46: skos:exactMatch disjoint with skos:broadMatch / skos:relatedMatch ─

  "S46" should "reject a pair linked by both skos:exactMatch and skos:broadMatch" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:exactMatch ex:B .
         |ex:A skos:broadMatch ex:B .""".stripMargin
    ).valid shouldBe false
  }

  it should "reject a pair linked by both skos:exactMatch and skos:relatedMatch" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:exactMatch   ex:B .
         |ex:A skos:relatedMatch ex:B .""".stripMargin
    ).valid shouldBe false
  }

  it should "accept a concept with only skos:exactMatch (no broadMatch/relatedMatch to same target)" in {
    checkDisjoint(
      s"""@prefix skos: <$SKOS> .
         |@prefix ex: <http://example.org/> .
         |ex:A skos:exactMatch ex:B .
         |ex:A skos:broadMatch ex:C .""".stripMargin
    ).valid shouldBe true
  }
}
