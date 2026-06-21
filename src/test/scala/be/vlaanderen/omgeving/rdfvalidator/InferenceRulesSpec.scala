package be.vlaanderen.omgeving.rdfvalidator

import org.apache.jena.rdf.model.{ModelFactory, ResourceFactory}
import org.apache.jena.reasoner.rulesys.{GenericRuleReasoner, Rule}
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.vocabulary.RDF
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class InferenceRulesSpec extends AnyFlatSpec with Matchers {

  private def parseTTL(ttl: String) = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  private def reasonerFromFile: GenericRuleReasoner = {
    val url = getClass.getClassLoader.getResource("rules/domain-range-subproperty.rules").toString
    new GenericRuleReasoner(Rule.rulesFromURL(url))
  }

  private def infer(ontologyTtl: String, dataTtl: String,
                    reasoner: GenericRuleReasoner = reasonerFromFile) = {
    val ontology = parseTTL(ontologyTtl)
    val data     = parseTTL(dataTtl)
    RdfUtils.inferTriples(data, ontology, reasoner)
  }

  private def res(uri: String) = ResourceFactory.createResource(uri)

  // ── owl:equivalentClass ────────────────────────────────────────────────────

  "equivalentClassRule1" should "infer rdf:type of equivalent class (forward)" in {
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:Person owl:equivalentClass ex:Human .""",
      """@prefix ex: <http://example.org/> .
         ex:Alice a ex:Person ."""
    )
    result.contains(res("http://example.org/Alice"), RDF.`type`, res("http://example.org/Human")) shouldBe true
  }

  "equivalentClassRule2" should "infer rdf:type of equivalent class (reverse)" in {
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:Person owl:equivalentClass ex:Human .""",
      """@prefix ex: <http://example.org/> .
         ex:Bob a ex:Human ."""
    )
    result.contains(res("http://example.org/Bob"), RDF.`type`, res("http://example.org/Person")) shouldBe true
  }

  "equivalentClassRule" should "not type instances as blank-node class expressions" in {
    val result = infer(
      """@prefix owl:  <http://www.w3.org/2002/07/owl#> .
         @prefix ex:   <http://example.org/> .
         ex:Foo owl:equivalentClass [ owl:onProperty ex:p ; owl:someValuesFrom ex:Bar ] .""",
      """@prefix ex: <http://example.org/> .
         ex:Alice a ex:Foo ."""
    )
    // Alice should not be typed as any blank node
    val blankTypes = result.listStatements(res("http://example.org/Alice"), RDF.`type`, null)
      .asScala.filter(_.getObject.isAnon).toList
    blankTypes shouldBe empty
  }

  // ── owl:equivalentProperty ─────────────────────────────────────────────────

  "equivalentPropertyRule1" should "infer property assertion via equivalent property (forward)" in {
    val maker   = ResourceFactory.createProperty("http://example.org/maker")
    val creator = ResourceFactory.createProperty("http://example.org/creator")
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:maker owl:equivalentProperty ex:creator .""",
      """@prefix ex: <http://example.org/> .
         ex:Alice ex:maker ex:Doc ."""
    )
    result.contains(res("http://example.org/Alice"), creator, res("http://example.org/Doc")) shouldBe true
  }

  "equivalentPropertyRule2" should "infer property assertion via equivalent property (reverse)" in {
    val maker = ResourceFactory.createProperty("http://example.org/maker")
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:maker owl:equivalentProperty ex:creator .""",
      """@prefix ex: <http://example.org/> .
         ex:Bob ex:creator ex:Report ."""
    )
    result.contains(res("http://example.org/Bob"), maker, res("http://example.org/Report")) shouldBe true
  }

  // ── owl:SymmetricProperty ──────────────────────────────────────────────────

  "symmetricPropertyRule" should "infer reverse direction for symmetric properties" in {
    val sibling = ResourceFactory.createProperty("http://example.org/sibling")
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:sibling a owl:SymmetricProperty .""",
      """@prefix ex: <http://example.org/> .
         ex:Alice ex:sibling ex:Bob ."""
    )
    result.contains(res("http://example.org/Bob"), sibling, res("http://example.org/Alice")) shouldBe true
  }

  // ── owl:TransitiveProperty ─────────────────────────────────────────────────

  "transitivePropertyRule" should "infer transitive closure for a 2-step chain" in {
    val before = ResourceFactory.createProperty("http://example.org/before")
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:before a owl:TransitiveProperty .""",
      """@prefix ex: <http://example.org/> .
         ex:A ex:before ex:B .
         ex:B ex:before ex:C ."""
    )
    result.contains(res("http://example.org/A"), before, res("http://example.org/C")) shouldBe true
  }

  it should "infer transitive closure for a 3-step chain" in {
    val before = ResourceFactory.createProperty("http://example.org/before")
    val result = infer(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:before a owl:TransitiveProperty .""",
      """@prefix ex: <http://example.org/> .
         ex:A ex:before ex:B .
         ex:B ex:before ex:C .
         ex:C ex:before ex:D ."""
    )
    result.contains(res("http://example.org/A"), before, res("http://example.org/D")) shouldBe true
  }

  // ── extractPropertyChainRules ──────────────────────────────────────────────

  "extractPropertyChainRules" should "infer via a 2-element property chain" in {
    val chain = ResourceFactory.createProperty("http://example.org/r")
    val ontology = parseTTL(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:r owl:propertyChainAxiom ( ex:p1 ex:p2 ) ."""
    )
    val data = parseTTL(
      """@prefix ex: <http://example.org/> .
         ex:A ex:p1 ex:B .
         ex:B ex:p2 ex:C ."""
    )
    val chainRules = OntologySubsets.extractPropertyChainRules(ontology)
    chainRules should not be empty
    val reasoner = new GenericRuleReasoner(chainRules.asJava)
    val result = RdfUtils.inferTriples(data, ontology, reasoner)
    result.contains(res("http://example.org/A"), chain, res("http://example.org/C")) shouldBe true
  }

  it should "generate the correct number of rules from prov-o style chains" in {
    val ontology = parseTTL(
      """@prefix owl:  <http://www.w3.org/2002/07/owl#> .
         @prefix prov: <http://www.w3.org/ns/prov#> .
         prov:agent owl:propertyChainAxiom ( prov:qualifiedDelegation prov:agent ) .
         prov:entity owl:propertyChainAxiom ( prov:qualifiedUsage prov:entity ) ."""
    )
    val rules = OntologySubsets.extractPropertyChainRules(ontology)
    rules.size shouldBe 2
  }

  it should "skip chains longer than 2 elements and not generate a rule" in {
    val ontology = parseTTL(
      """@prefix owl: <http://www.w3.org/2002/07/owl#> .
         @prefix ex:  <http://example.org/> .
         ex:r owl:propertyChainAxiom ( ex:p1 ex:p2 ex:p3 ) ."""
    )
    val rules = OntologySubsets.extractPropertyChainRules(ontology)
    rules shouldBe empty
  }
}
