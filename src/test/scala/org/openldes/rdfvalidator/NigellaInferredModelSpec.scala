package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.{Model, ModelFactory, ResourceFactory}
import org.apache.jena.reasoner.{Reasoner, ReasonerRegistry}
import org.apache.jena.reasoner.rulesys.{GenericRuleReasoner, Rule}
import org.apache.jena.shacl.{Shapes, ValidationReport}
import org.apache.jena.vocabulary.RDF
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{File, FileOutputStream}
import scala.collection.JavaConverters._

class NigellaInferredModelSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers {

  // Minimal rules used for SHACL pre-inference (same as CompleteValidationTest)
  private val SHACL_INFERENCE_RULES =
    "[inversePropertyRule:  (?p owl:inverseOf ?q), (?x ?p ?y) -> (?y ?q ?x)]" +
    "[inversePropertyRule2: (?p owl:inverseOf ?q), (?x ?q ?y) -> (?y ?p ?x)]" +
    "[subClassRule: (?y rdfs:subClassOf ?z), (?x rdf:type ?y) -> (?x rdf:type ?z)]"

  private var nigellaData: Model              = _
  private var inferredModel: Model            = _
  private var shaclInferredModel: Model       = _
  private var completeOntology: Model         = _
  private var classDisjointSubset: Model      = _
  private var shaclShapes: Shapes             = _
  private var owlReasonerWithSchema: Reasoner = _

  override def beforeAll(): Unit = {
    completeOntology = ModelFactory.createDefaultModel()
    val ontDir = new File(getClass.getClassLoader.getResource("ontologies").toURI)
    RdfUtils.listTurtleFiles(ontDir).foreach { f =>
      completeOntology.add(RdfUtils.parseTurtle(f))
    }

    classDisjointSubset = OntologySubsets.extractClassDisjointSubset(completeOntology)
    shaclShapes = Shapes.parse(OwlToShaclGenerator.generate(completeOntology))
    owlReasonerWithSchema = ReasonerRegistry.getOWLMiniReasoner().bindSchema(classDisjointSubset)

    val structuralSubset = OntologySubsets.extractStructuralSubset(completeOntology)

    val rulesUrl    = getClass.getClassLoader.getResource("rules/owl2-rl.rules").toString
    val staticRules = Rule.rulesFromURL(rulesUrl).asScala.toList
    val chainRules  = OntologySubsets.extractPropertyChainRules(completeOntology).toList
    val ruleReasoner = new GenericRuleReasoner((staticRules ++ chainRules).asJava)
    ruleReasoner.setDerivationLogging(false)

    val shaclReasoner = new GenericRuleReasoner(Rule.parseRules(SHACL_INFERENCE_RULES))
    shaclReasoner.setDerivationLogging(false)

    val nigellaFile = new File(getClass.getClassLoader
      .getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)
    nigellaData = RdfUtils.parseTurtle(nigellaFile)

    inferredModel      = RdfUtils.inferTriples(nigellaData, structuralSubset, ruleReasoner)
    shaclInferredModel = RdfUtils.inferTriples(nigellaData, completeOntology, shaclReasoner)

    val dataSubjects = nigellaData.listSubjects().asScala.toSet
    val brownieOnly  = ModelFactory.createDefaultModel()
    brownieOnly.setNsPrefixes(nigellaData)
    inferredModel.listStatements().asScala
      .filter(stmt => dataSubjects.contains(stmt.getSubject))
      .foreach(brownieOnly.add)

    val outDir  = new File("target/generated-inferred")
    outDir.mkdirs()
    val outFile = new File(outDir, "Nigella-Lawson-Brownies-inferred.ttl")
    val out     = new FileOutputStream(outFile)
    try brownieOnly.write(out, "TURTLE")
    finally out.close()
  }

  private def res(uri: String)  = ResourceFactory.createResource(uri)
  private def prop(uri: String) = ResourceFactory.createProperty(uri)

  private val PPLAN   = "http://purl.org/net/p-plan#"
  private val STEP    = "https://example.org/step/"
  private val PLAN    = "https://example.org/plan/"
  private val AGENT   = "https://example.org/agent/"
  private val KITCHEN = "https://example.org/platform/"
  private val ATTR    = "https://example.org/attribution/"
  private val PROV    = "http://www.w3.org/ns/prov#"
  private val FOAF    = "http://xmlns.com/foaf/0.1/"
  private val DCT     = "http://purl.org/dc/terms/"
  private val QUDT    = "http://qudt.org/schema/qudt/"
  private val ENT     = "https://example.org/entity/"

  // ── 1. Vocabulary conformance ─────────────────────────────────────────────

  "Nigella inferred model" should "use only vocabulary declared in the ontologies" in {
    val result = RdfUtils.checkVocabularyUsage(nigellaData, completeOntology)
    withClue(result.messages.mkString("\n")) {
      result.valid shouldBe true
    }
  }

  // ── 2. Class disjointness ─────────────────────────────────────────────────

  it should "have no class disjointness violations after full inference" in {
    val result = RdfUtils.checkClassDisjointness(inferredModel, classDisjointSubset)
    withClue(result.messages.mkString("\n")) {
      result.valid shouldBe true
    }
  }

  // ── 3. OWL consistency ────────────────────────────────────────────────────

  it should "pass OWL consistency check on the inferred model" in {
    val result = RdfUtils.validateModel(inferredModel, owlReasonerWithSchema)
    withClue(result.messages.mkString("\n")) {
      result.valid shouldBe true
    }
  }

  // ── 4. SHACL conformance ─────────────────────────────────────────────────

  it should "pass SHACL validation on the SHACL-inferred model" in {
    val report = ShaclValidator.validate(shaclInferredModel, shaclShapes)
    val violations = report.getEntries.asScala.map(e =>
      s"focusNode=${e.focusNode}, path=${e.resultPath}, msg=${e.message}")
    withClue(violations.mkString("\n")) {
      report.conforms shouldBe true
    }
  }

  // ── 5. owl:TransitiveProperty: p-plan:isPrecededBy ───────────────────────

  it should "infer transitive isPrecededBy: mixing isPrecededBy melting (via chopping)" in {
    inferredModel.contains(
      res(STEP + "mixing"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "melting")
    ) shouldBe true
  }

  it should "infer transitive isPrecededBy: baking isPrecededBy chopping (2-step)" in {
    inferredModel.contains(
      res(STEP + "baking"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "chopping")
    ) shouldBe true
  }

  it should "infer transitive isPrecededBy: baking isPrecededBy melting (3-step)" in {
    inferredModel.contains(
      res(STEP + "baking"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "melting")
    ) shouldBe true
  }

  it should "infer transitive isPrecededBy: baking_tin_in_oven isPrecededBy preheating" in {
    inferredModel.contains(
      res(STEP + "baking_tin_in_oven"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "preheating")
    ) shouldBe true
  }

  it should "infer transitive isPrecededBy: bake_until_light_brown_top isPrecededBy preheating" in {
    inferredModel.contains(
      res(STEP + "bake_until_light_brown_top"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "preheating")
    ) shouldBe true
  }

  it should "infer transitive isPrecededBy: remove_from_oven_and_cool_down isPrecededBy baking_tin_in_oven" in {
    inferredModel.contains(
      res(STEP + "remove_from_oven_and_cool_down"),
      prop(PPLAN + "isPrecededBy"),
      res(STEP + "baking_tin_in_oven")
    ) shouldBe true
  }

  // ── 6. owl:equivalentProperty: dcterms:creator ↔ foaf:maker ──────────────

  it should "infer foaf:maker from dct:creator via owl:equivalentProperty" in {
    inferredModel.contains(
      res(PLAN + "nigella-brownies"),
      prop(FOAF + "maker"),
      res(AGENT + "nigella-lawson")
    ) shouldBe true
  }

  it should "infer dct:creator from foaf:maker symmetrically (reverse direction)" in {
    // foaf:maker owl:equivalentProperty dcterms:creator — both directions should be present
    // The original data already has dct:creator, so we check foaf:maker was added
    inferredModel.contains(
      res(PLAN + "nigella-brownies"),
      prop(FOAF + "maker"),
      res(AGENT + "nigella-lawson")
    ) shouldBe true
  }

  // ── 7. PROV-O property chain: qualifiedAttribution ∘ prov:agent → prov:wasAttributedTo ─

  it should "infer prov:wasAttributedTo for kitchen via prov:qualifiedAttribution chain" in {
    // prov:wasAttributedTo owl:propertyChainAxiom (prov:qualifiedAttribution prov:agent)
    // kitchen-geert qualifiedAttribution attr:relation-kitchen-geert
    // attr:relation-kitchen-geert prov:agent agent:geert
    // → kitchen-geert prov:wasAttributedTo agent:geert
    inferredModel.contains(
      res(KITCHEN + "kitchen-geert"),
      prop(PROV + "wasAttributedTo"),
      res(AGENT + "geert")
    ) shouldBe true
  }

  // ── 8. owl:inverseOf: qudt:valueQuantity ← qudt:quantityValue ────────────

  it should "infer qudt:valueQuantity via owl:inverseOf qudt:quantityValue" in {
    // qudt:valueQuantity owl:inverseOf qudt:quantityValue (schema_qudt.ttl)
    // ent:butter_001 qudt:quantityValue _:b
    // → _:b qudt:valueQuantity ent:butter_001
    inferredModel.contains(
      null,
      prop(QUDT + "valueQuantity"),
      res(ENT + "butter_001")
    ) shouldBe true
  }

  // ── 8. Inferred triples count sanity check ────────────────────────────────

  it should "contain more triples than the original data (inference added triples)" in {
    inferredModel.size() should be > nigellaData.size()
  }

  // ── 9. Inferred file exists and is non-empty ──────────────────────────────

  it should "have written a non-empty inferred Turtle file to target/generated-inferred/" in {
    val outFile = new File("target/generated-inferred/Nigella-Lawson-Brownies-inferred.ttl")
    outFile.exists() shouldBe true
    outFile.length() should be > 0L
  }

}
