package org.openldes.rdfvalidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RdfUtilsTest {

    private static final Logger LOG = LoggerFactory.getLogger(RdfUtilsTest.class);

    private Model browniesModel;
    private List<File> exampleFiles;
    private GenericRuleReasoner ruleReasoner;
    private JsonNode frame;
    private JsonNode browniesJsonLd;

    @BeforeAll
    void setUp() throws URISyntaxException {
        File browniesFile = new File(
                getClass().getClassLoader()
                        .getResource("examples/Nigella-Lawson-brownies/Nigella-Lawson-Brownies.ttl")
                        .toURI());
        browniesModel = RdfUtils.parseTurtle(browniesFile);

        File examplesDir = new File(
                getClass().getClassLoader().getResource("examples").toURI());
        exampleFiles = JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(examplesDir));

        String rulesUrl = getClass().getClassLoader()
                .getResource("rules/owl2-rl.rules").toString();
        ruleReasoner = new GenericRuleReasoner(Rule.rulesFromURL(rulesUrl));

        String framePath = getClass().getClassLoader()
                .getResource("frame/observation-frame.json").getPath();
        frame = RdfUtils.loadFrame(framePath);

        browniesJsonLd = RdfUtils.modelToJsonLd(browniesModel).get();
    }

    private static Model parseTTL(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m);
        return m;
    }

    // --- parseTurtle ---

    @Test
    void parseTurtle_loadsValidTurtleFileIntoNonEmptyModel() {
        assertFalse(browniesModel.isEmpty(),
                "Nigella-Lawson-Brownies.ttl must produce a non-empty model");
    }

    // --- listTurtleFiles ---

    @Test
    void listTurtleFiles_returnsOnlyTtlFiles() {
        assertFalse(exampleFiles.isEmpty(),
                "The examples directory must contain at least one TTL file");
        for (File f : exampleFiles) {
            assertTrue(f.getName().endsWith(".ttl"),
                    "Unexpected file in the list: " + f.getName());
        }
    }

    @Test
    void listTurtleFiles_recursesIntoSubdirectories() {
        boolean found = exampleFiles.stream()
                .anyMatch(f -> f.getName().equals("Nigella-Lawson-Brownies.ttl"));
        assertTrue(found, "Nigella-Lawson-Brownies.ttl not found in the recursive file list");
    }

    // --- checkVocabularyUsage ---

    @Test
    void checkVocabularyUsage_validWhenAllPredicatesAndTypesDeclared() {
        Model ontology = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Person a ex:Class .\n" +
                "ex:name   a ex:Property .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Alice a ex:Person ; ex:name \"Alice\" .\n");
        ValidationResult result = RdfUtils.checkVocabularyUsage(data, ontology);
        List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
        msgs.forEach(m -> LOG.warn("❌ [VOCAB] {}", m));
        assertTrue(result.valid(),
                "Unexpected vocabulary errors:\n" + String.join("\n", msgs));
        assertTrue(msgs.isEmpty(), "Messages must be empty for valid vocabulary");
    }

    @Test
    void checkVocabularyUsage_reportsUnknownPredicate() {
        Model ontology = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:KnownProp a ex:Property .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Alice ex:unknownProp \"hello\" .\n");
        ValidationResult result = RdfUtils.checkVocabularyUsage(data, ontology);
        List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
        assertFalse(result.valid(), "An unknown predicate should have produced an error");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("http://example.org/unknownProp")),
                "The error message must mention the unknown property URI");
    }

    @Test
    void checkVocabularyUsage_reportsUnknownClassInRdfType() {
        Model ontology = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:placeholder a ex:placeholder .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Alice a ex:UnknownClass .\n");
        ValidationResult result = RdfUtils.checkVocabularyUsage(data, ontology);
        List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
        assertFalse(result.valid(), "An unknown class should have produced an error");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("http://example.org/UnknownClass")),
                "The error message must mention the unknown class URI");
    }

    // --- checkClassDisjointness ---

    @Test
    void checkClassDisjointness_reportsViolationWhenResourceHasBothTypes() {
        Model subset = parseTTL(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix ex:  <http://example.org/> .\n" +
                "ex:A owl:disjointWith ex:B .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:thing a ex:A , ex:B .\n");
        ValidationResult result = RdfUtils.checkClassDisjointness(data, subset);
        List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
        assertFalse(result.valid(), "A resource typed as two disjoint classes must produce a violation");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("http://example.org/thing")),
                "The violation message must mention the offending resource");
    }

    @Test
    void checkClassDisjointness_returnsValidWhenNoOverlap() {
        Model subset = parseTTL(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix ex:  <http://example.org/> .\n" +
                "ex:A owl:disjointWith ex:B .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:thing1 a ex:A .\n" +
                "ex:thing2 a ex:B .\n");
        ValidationResult result = RdfUtils.checkClassDisjointness(data, subset);
        assertTrue(result.valid(),
                "Resources typed as different disjoint classes must not produce violations");
    }

    // --- inferTriples ---

    @Test
    void inferTriples_addsRdfTypeTriplesViaSubClassOf() {
        Model ontology = parseTTL(
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:TemperatureSensor rdfs:subClassOf ex:Sensor .\n");
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:DHT22 a ex:TemperatureSensor .\n");

        Model result = RdfUtils.inferTriples(data, ontology, ruleReasoner);

        assertTrue(result.contains(
                result.createResource("http://example.org/DHT22"),
                RDF.type,
                result.createResource("http://example.org/Sensor")),
                "ex:DHT22 must also be typed as ex:Sensor via subClassOf inference");
    }

    // --- validateModel ---

    @Test
    void validateModel_returnValidForConsistentOwlModel() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Alice a ex:Person .\n" +
                "ex:Bob   a ex:Person .\n");
        ValidationResult result = RdfUtils.validateModel(data, ReasonerRegistry.getOWLMiniReasoner());
        List<String> msgs = JavaConverters.seqAsJavaList(result.messages());
        msgs.forEach(m -> LOG.warn("❌ [OWL] {}", m));
        assertTrue(result.valid(),
                "A consistent OWL model must not produce any errors:\n" + String.join("\n", msgs));
    }

    // --- modelToJsonLd ---

    @Test
    void modelToJsonLd_returnsSomeForNonEmptyModel() {
        scala.Option<JsonNode> result = RdfUtils.modelToJsonLd(browniesModel);
        assertTrue(result.isDefined(),
                "modelToJsonLd must return a value for a non-empty model");
    }

    @Test
    void modelToJsonLd_returnsNoneForEmptyModel() {
        scala.Option<JsonNode> result = RdfUtils.modelToJsonLd(ModelFactory.createDefaultModel());
        assertTrue(result.isEmpty(),
                "modelToJsonLd must return None for an empty model");
    }

    // --- extractGraph ---

    @Test
    void extractGraph_returnsSomeArrayWhenGraphKeyPresent() throws Exception {
        JsonNode json = new ObjectMapper()
                .readTree("{\"@graph\": [{\"@id\": \"http://example.org/a\"}]}");
        scala.Option<JsonNode> result = RdfUtils.extractGraph(json);
        assertTrue(result.isDefined(), "extractGraph must return a value when @graph is present");
        assertTrue(result.get().isArray(), "The returned @graph element must be an array");
    }

    @Test
    void extractGraph_returnsNoneWhenGraphKeyAbsent() throws Exception {
        JsonNode json = new ObjectMapper().readTree("{\"@id\": \"http://example.org/a\"}");
        scala.Option<JsonNode> result = RdfUtils.extractGraph(json);
        assertTrue(result.isEmpty(), "extractGraph must return None when @graph is absent");
    }

    // --- loadFrame / frameJsonLd ---

    @Test
    void loadFrame_loadsObservationFrameFile() {
        assertNotNull(frame, "loadFrame must not return null");
        assertTrue(frame.has("@type"), "The observation frame must contain a @type key");
    }

    @Test
    void frameJsonLd_returnsFramedResultForBrowniesModel() {
        scala.Option<JsonNode> result = RdfUtils.frameJsonLd(browniesJsonLd, frame);
        assertTrue(result.isDefined(),
                "frameJsonLd must return a value for the Nigella Lawson Brownies model");
    }

    @Test
    void frameJsonLd_includesObservationNodeInGraphAfterFraming() {
        scala.Option<JsonNode> framedOpt = RdfUtils.frameJsonLd(browniesJsonLd, frame);
        assertTrue(framedOpt.isDefined(), "Framing must not produce an empty result");
        scala.Option<JsonNode> graphOpt = RdfUtils.extractGraph(framedOpt.get());
        assertTrue(graphOpt.isDefined(), "@graph must be present in the framed result");
        assertTrue(graphOpt.get().size() > 0,
                "@graph must not be empty after framing Nigella-Lawson-Brownies.ttl");
    }
}
