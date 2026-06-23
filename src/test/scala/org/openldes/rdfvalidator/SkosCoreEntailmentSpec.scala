package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.{Model, ModelFactory, ResourceFactory}
import org.apache.jena.reasoner.rulesys.{GenericRuleReasoner, Rule}
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.vocabulary.RDF
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SkosCoreEntailmentSpec extends AnyFlatSpec with Matchers {

  private val SKOS = "http://www.w3.org/2004/02/skos/core#"
  private val EX   = "http://example.org/skos/"
  private val EXT  = "http://external.org/skos/"

  private lazy val skosOntology: Model = loadClasspath("ontologies/skos.ttl")

  private lazy val reasoner: GenericRuleReasoner = {
    val url = getClass.getClassLoader.getResource("rules/owl2-rl.rules").toString
    new GenericRuleReasoner(Rule.rulesFromURL(url))
  }

  private def loadClasspath(path: String): Model = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create()
      .source(getClass.getClassLoader.getResourceAsStream(path))
      .lang(Lang.TTL)
      .parse(m)
    m
  }

  private def infer(exampleFile: String): Model =
    RdfUtils.inferTriples(loadClasspath(s"examples/skos/$exampleFile"), skosOntology, reasoner)

  private def p(localName: String) = ResourceFactory.createProperty(SKOS + localName)
  private def r(uri: String)       = ResourceFactory.createResource(uri)

  // ── Hierarchical relations ─────────────────────────────────────────────────

  "S22: skos:broader" should "imply skos:broaderTransitive via rdfs:subPropertyOf" in {
    val result = infer("skos-hierarchical.ttl")
    result.contains(r(EX + "mammal"), p("broaderTransitive"), r(EX + "animal")) shouldBe true
  }

  "S25: skos:narrower" should "be inferred as the inverse of skos:broader" in {
    val result = infer("skos-hierarchical.ttl")
    result.contains(r(EX + "animal"), p("narrower"), r(EX + "mammal")) shouldBe true
  }

  "S22+S24: skos:broader" should "produce three broaderTransitive triples via subPropertyOf and transitivity" in {
    val result = infer("skos-transitivity.ttl")
    result.contains(r(EX + "a"), p("broaderTransitive"), r(EX + "b")) shouldBe true
    result.contains(r(EX + "b"), p("broaderTransitive"), r(EX + "c")) shouldBe true
    result.contains(r(EX + "a"), p("broaderTransitive"), r(EX + "c")) shouldBe true
  }

  "S24: skos:broaderTransitive" should "be transitive across a two-step chain" in {
    val result = infer("skos-hierarchical.ttl")
    result.contains(r(EX + "dog"), p("broaderTransitive"), r(EX + "animal")) shouldBe true
  }

  "S26: skos:narrowerTransitive" should "be inferred as the inverse of skos:broaderTransitive" in {
    val result = infer("skos-hierarchical.ttl")
    result.contains(r(EX + "animal"), p("narrowerTransitive"), r(EX + "dog")) shouldBe true
  }

  "S21: skos:broaderTransitive" should "imply skos:semanticRelation via rdfs:subPropertyOf" in {
    val result = infer("skos-hierarchical.ttl")
    result.contains(r(EX + "mammal"), p("semanticRelation"), r(EX + "animal")) shouldBe true
  }

  // ── Associative relations ──────────────────────────────────────────────────

  "S23: skos:related" should "be symmetric" in {
    val result = infer("skos-associative.ttl")
    result.contains(r(EX + "diving"), p("related"), r(EX + "swimming")) shouldBe true
  }

  "S21: skos:related" should "imply skos:semanticRelation via rdfs:subPropertyOf" in {
    val result = infer("skos-associative.ttl")
    result.contains(r(EX + "swimming"), p("semanticRelation"), r(EX + "diving")) shouldBe true
  }

  // ── Mapping relations ──────────────────────────────────────────────────────

  "S44: skos:exactMatch" should "be symmetric" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EXT + "canine"), p("exactMatch"), r(EX + "dog")) shouldBe true
  }

  "S45: skos:exactMatch" should "be transitive" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "dog"), p("exactMatch"), r(EXT + "feline")) shouldBe true
  }

  "S42: skos:exactMatch" should "imply skos:closeMatch via rdfs:subPropertyOf" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "dog"), p("closeMatch"), r(EXT + "canine")) shouldBe true
  }

  "S40+S42: skos:exactMatch" should "imply skos:mappingRelation via closeMatch subPropertyOf chain" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "dog"), p("mappingRelation"), r(EXT + "canine")) shouldBe true
  }

  "S44: skos:closeMatch" should "be symmetric" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EXT + "canine"), p("closeMatch"), r(EX + "dog")) shouldBe true
  }

  "S43: skos:broadMatch" should "imply skos:narrowMatch on the inverse resource" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "animal"), p("narrowMatch"), r(EX + "mammal")) shouldBe true
  }

  "S41: skos:broadMatch" should "imply skos:broader via rdfs:subPropertyOf" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "mammal"), p("broader"), r(EX + "animal")) shouldBe true
  }

  "S44: skos:relatedMatch" should "be symmetric" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "jogging"), p("relatedMatch"), r(EX + "running")) shouldBe true
  }

  "S41: skos:relatedMatch" should "imply skos:related via rdfs:subPropertyOf" in {
    val result = infer("skos-mapping.ttl")
    result.contains(r(EX + "running"), p("related"), r(EX + "jogging")) shouldBe true
  }

  // ── ConceptScheme relations ────────────────────────────────────────────────

  "S8: skos:hasTopConcept" should "imply skos:topConceptOf on the inverse resource" in {
    val result = infer("skos-concept-scheme.ttl")
    result.contains(r(EX + "rootConcept"), p("topConceptOf"), r(EX + "scheme")) shouldBe true
  }

  "S7: skos:topConceptOf" should "imply skos:inScheme via rdfs:subPropertyOf" in {
    val result = infer("skos-concept-scheme.ttl")
    result.contains(r(EX + "rootConcept"), p("inScheme"), r(EX + "scheme")) shouldBe true
  }

  // ── Type inference via domain/range ───────────────────────────────────────

  "S19: subject of skos:broader" should "be inferred as skos:Concept via domain chain" in {
    val result = infer("skos-type-inference.ttl")
    result.contains(r(EX + "topic1"), RDF.`type`, r(SKOS + "Concept")) shouldBe true
  }

  "S20: object of skos:broader" should "be inferred as skos:Concept via range chain" in {
    val result = infer("skos-type-inference.ttl")
    result.contains(r(EX + "topic2"), RDF.`type`, r(SKOS + "Concept")) shouldBe true
  }
}
