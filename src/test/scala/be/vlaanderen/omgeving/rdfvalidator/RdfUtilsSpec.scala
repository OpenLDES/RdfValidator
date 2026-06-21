package be.vlaanderen.omgeving.rdfvalidator

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.reasoner.ReasonerRegistry
import org.apache.jena.reasoner.rulesys.{GenericRuleReasoner, Rule}
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.vocabulary.RDF
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.collection.JavaConverters._

class RdfUtilsSpec extends AnyFlatSpec with Matchers {

  private def parseTTL(ttl: String) = {
    val m = ModelFactory.createDefaultModel()
    RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m)
    m
  }

  private def examplesDir: File =
    new File(getClass.getClassLoader.getResource("examples").toURI)

  // --- parseTurtle ---

  "parseTurtle" should "load a valid turtle file into a non-empty model" in {
    val f = new File(getClass.getClassLoader.getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)
    RdfUtils.parseTurtle(f).isEmpty shouldBe false
  }

  // --- listTurtleFiles ---

  "listTurtleFiles" should "return only .ttl files" in {
    val files = RdfUtils.listTurtleFiles(examplesDir)
    files should not be empty
    files.foreach(f => f.getName should endWith(".ttl"))
  }

  it should "recurse into subdirectories" in {
    val names = RdfUtils.listTurtleFiles(examplesDir).map(_.getName)
    names should contain("Nigella-Lawson-Brownies.ttl")
  }

  // --- checkVocabularyUsage ---

  "checkVocabularyUsage" should "return valid when all predicates and types are declared in the ontology" in {
    val ontology = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Person a ex:Class .
      ex:name   a ex:Property .
    """)
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:name "Alice" .
    """)
    val result = RdfUtils.checkVocabularyUsage(data, ontology)
    result.valid shouldBe true
    result.messages shouldBe empty
  }

  it should "report an unknown predicate" in {
    val ontology = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:KnownProp a ex:Property .
    """)
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice ex:unknownProp "hello" .
    """)
    val result = RdfUtils.checkVocabularyUsage(data, ontology)
    result.valid shouldBe false
    result.messages.exists(_.contains("http://example.org/unknownProp")) shouldBe true
  }

  it should "report an unknown class used in rdf:type" in {
    val ontology = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:placeholder a ex:placeholder .
    """)
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:UnknownClass .
    """)
    val result = RdfUtils.checkVocabularyUsage(data, ontology)
    result.valid shouldBe false
    result.messages.exists(_.contains("http://example.org/UnknownClass")) shouldBe true
  }

  // --- inferTriples ---

  "inferTriples" should "add rdf:type triples inferred via subClassOf" in {
    val ontology = parseTTL("""
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix ex:   <http://example.org/> .
      ex:TemperatureSensor rdfs:subClassOf ex:Sensor .
    """)
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:DHT22 a ex:TemperatureSensor .
    """)
    val rulesUrl = getClass.getClassLoader.getResource("rules/domain-range-subproperty.rules").toString
    val reasoner = new GenericRuleReasoner(Rule.rulesFromURL(rulesUrl))

    val result = RdfUtils.inferTriples(data, ontology, reasoner)

    val dht22  = result.createResource("http://example.org/DHT22")
    val sensor = result.createResource("http://example.org/Sensor")
    result.contains(dht22, RDF.`type`, sensor) shouldBe true
  }

  // --- validateModel ---

  "validateModel" should "return valid for a consistent OWL model" in {
    val data = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person .
      ex:Bob   a ex:Person .
    """)
    val result = RdfUtils.validateModel(data, ReasonerRegistry.getOWLMiniReasoner())
    result.valid shouldBe true
    result.messages shouldBe empty
  }

  // --- modelToJsonLd ---

  "modelToJsonLd" should "return Some for a non-empty model" in {
    val f = new File(getClass.getClassLoader.getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)
    RdfUtils.modelToJsonLd(RdfUtils.parseTurtle(f)) should not be empty
  }

  it should "return None for an empty model" in {
    RdfUtils.modelToJsonLd(ModelFactory.createDefaultModel()) shouldBe None
  }

  // --- extractGraph ---

  "extractGraph" should "return Some array when @graph key is present" in {
    val json = new ObjectMapper().readTree("""{"@graph": [{"@id": "http://example.org/a"}]}""")
    val result = RdfUtils.extractGraph(json)
    result should not be empty
    result.get.isArray shouldBe true
  }

  it should "return None when @graph key is absent" in {
    val json = new ObjectMapper().readTree("""{"@id": "http://example.org/a"}""")
    RdfUtils.extractGraph(json) shouldBe None
  }

  // --- loadFrame / frameJsonLd ---

  "loadFrame" should "load the observation frame file" in {
    val path  = getClass.getClassLoader.getResource("frame/observation-frame.json").getPath
    val frame = RdfUtils.loadFrame(path)
    frame should not be null
    frame.has("@type") shouldBe true
  }

  "frameJsonLd" should "return a framed result for the Nigella Lawson Brownies model" in {
    val f     = new File(getClass.getClassLoader.getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)
    val model = RdfUtils.parseTurtle(f)
    val path  = getClass.getClassLoader.getResource("frame/observation-frame.json").getPath
    val frame = RdfUtils.loadFrame(path)
    val jsonLd = RdfUtils.modelToJsonLd(model).get
    RdfUtils.frameJsonLd(jsonLd, frame) should not be empty
  }

  it should "include nodes from Nigella-Lawson-Brownies.ttl in the @graph after framing" in {
    val f     = new File(getClass.getClassLoader.getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl").toURI)
    val model = RdfUtils.parseTurtle(f)
    val path  = getClass.getClassLoader.getResource("frame/observation-frame.json").getPath
    val frame = RdfUtils.loadFrame(path)
    val jsonLd = RdfUtils.modelToJsonLd(model).get
    val graph  = RdfUtils.frameJsonLd(jsonLd, frame).flatMap(RdfUtils.extractGraph)
    graph should not be empty
    graph.get.size() should be > 0
  }

  // --- deriveFrame ---

  "deriveFrame" should "return an empty frame for an empty model" in {
    val frame = RdfUtils.deriveFrame(ModelFactory.createDefaultModel())
    frame.has("@context") shouldBe true
    frame.has("@type") shouldBe false
  }

  it should "include @omitDefault true in the frame" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person .
    """)
    val frame = RdfUtils.deriveFrame(model)
    frame.get("@omitDefault").asBoolean() shouldBe true
  }

  it should "include the type from the model as @type with full URI" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:name "Alice" .
    """)
    val frame = RdfUtils.deriveFrame(model)
    frame.get("@type").asText() shouldBe "http://example.org/Person"
  }

  it should "set @embed @never, @omitDefault true and @default null on resource-valued properties" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:knows ex:Bob .
      ex:Bob   a ex:Person .
    """)
    val frame    = RdfUtils.deriveFrame(model)
    val propNode = frame.get("http://example.org/knows")
    propNode.get("@embed").asText() shouldBe "@never"
    propNode.get("@omitDefault").asBoolean() shouldBe true
    propNode.get("@default").isNull shouldBe true
  }

  it should "add @type @id to @context for resource-valued properties" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:knows ex:Bob .
      ex:Bob   a ex:Person .
    """)
    val frame   = RdfUtils.deriveFrame(model)
    val ctxProp = frame.get("@context").get("http://example.org/knows")
    ctxProp.get("@id").asText() shouldBe "http://example.org/knows"
    ctxProp.get("@type").asText() shouldBe "@id"
  }

  it should "infer @type of linked resources in a resource-valued property" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:knows ex:Bob .
      ex:Bob   a ex:Person .
    """)
    val frame = RdfUtils.deriveFrame(model)
    frame.get("http://example.org/knows").get("@type").asText() shouldBe
      "http://example.org/Person"
  }

  it should "infer @type for a property with a consistent non-string datatype" in {
    val model = parseTTL("""
      @prefix ex:  <http://example.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      ex:Alice a ex:Person ; ex:age "30"^^xsd:integer .
    """)
    val frame    = RdfUtils.deriveFrame(model)
    val propNode = frame.get("http://example.org/age")
    propNode.get("@type").asText() shouldBe "http://www.w3.org/2001/XMLSchema#integer"
    propNode.get("@default").isNull shouldBe true
    val ctxProp  = frame.get("@context").get("http://example.org/age")
    ctxProp.get("@id").asText() shouldBe "http://example.org/age"
    ctxProp.get("@type").asText() shouldBe "http://www.w3.org/2001/XMLSchema#integer"
  }

  it should "add per-language aliases to @context for language-tagged properties" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:name "Alice"@en .
      ex:Bob   a ex:Person ; ex:name "Bob"@en .
    """)
    val frame   = RdfUtils.deriveFrame(model)
    val context = frame.get("@context")
    context.get("name_en").get("@id").asText() shouldBe "http://example.org/name"
    context.get("name_en").get("@language").asText() shouldBe "en"
    frame.has("http://example.org/name") shouldBe true
  }

  it should "add separate aliases per language when multiple language tags exist" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:label "Alice"@en ; ex:label "Alice"@nl .
    """)
    val frame   = RdfUtils.deriveFrame(model)
    val context = frame.get("@context")
    context.has("label_en") shouldBe true
    context.has("label_nl") shouldBe true
    frame.get("http://example.org/label").get("@omitDefault").asBoolean() shouldBe true
  }

  it should "produce a flat @graph where each typed subject is a separate record" in {
    val model = parseTTL("""
      @prefix ex: <http://example.org/> .
      ex:Alice a ex:Person ; ex:knows ex:Bob .
      ex:Bob   a ex:Person .
    """)
    val frame  = RdfUtils.deriveFrame(model)
    val jsonLd = RdfUtils.modelToJsonLd(model).get
    val graph  = RdfUtils.frameJsonLd(jsonLd, frame).flatMap(RdfUtils.extractGraph)
    graph should not be empty
    graph.get.size() shouldBe 2
  }
}
