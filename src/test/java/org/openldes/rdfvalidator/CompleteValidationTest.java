package org.openldes.rdfvalidator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CompleteValidationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CompleteValidationTest.class);

    // Only owl:inverseOf and rdfs:subClassOf — no domain/range rules, to prevent
    // blank nodes from being typed as qudt:QuantityValue via rdfs:range.
    private static final String SHACL_INFERENCE_RULES =
            "[inversePropertyRule:  (?p owl:inverseOf ?q), (?x ?p ?y) -> (?y ?q ?x)]" +
            "[inversePropertyRule2: (?p owl:inverseOf ?q), (?x ?q ?y) -> (?y ?p ?x)]" +
            "[subClassRule: (?y rdfs:subClassOf ?z), (?x rdf:type ?y) -> (?x rdf:type ?z)]";

    private Model completeOntology;
    private Model classDisjointSubset;
    private Model propertyDisjointSubset;
    private Shapes generatedShaclShapes;
    private Reasoner owlReasonerWithSchema;
    private GenericRuleReasoner ruleReasoner;
    private GenericRuleReasoner shaclReasoner;
    private List<File> exampleFiles;

    @BeforeAll
    void setUp() throws URISyntaxException {
        completeOntology = ModelFactory.createDefaultModel();
        for (File f : javaFiles("ontologies")) {
            LOG.info("Loading ontology: {}", f.getName());
            completeOntology.add(RdfUtils.parseTurtle(f));
        }

        classDisjointSubset    = OntologySubsets.extractClassDisjointSubset(completeOntology);
        Model disjointSubset = classDisjointSubset;
        propertyDisjointSubset = OntologySubsets.extractPropertyDisjointSubset(completeOntology);

        Model shaclModel = OwlToShaclGenerator.generate(completeOntology);
        String s14Url = getClass().getClassLoader().getResource("shacl/skos-s14.ttl").toString();
        shaclModel.add(RDFDataMgr.loadModel(s14Url));
        generatedShaclShapes = Shapes.parse(shaclModel);

        owlReasonerWithSchema = ReasonerRegistry.getOWLMiniReasoner().bindSchema(disjointSubset);

        String rulesUrl = getClass().getClassLoader()
                .getResource("rules/owl2-rl.rules").toString();
        List<Rule> staticRules = Rule.rulesFromURL(rulesUrl);
        List<Rule> chainRules  = JavaConverters.seqAsJavaList(
                OntologySubsets.extractPropertyChainRules(completeOntology));
        List<Rule> allRules = new ArrayList<>(staticRules);
        allRules.addAll(chainRules);
        ruleReasoner = new GenericRuleReasoner(allRules);
        ruleReasoner.setDerivationLogging(false);

        shaclReasoner = new GenericRuleReasoner(Rule.parseRules(SHACL_INFERENCE_RULES));
        shaclReasoner.setDerivationLogging(false);

        exampleFiles = javaFiles("examples");
    }

    // ── 1. Properties used in examples are declared in the ontologies ─────────

    @Test
    void eachExampleUsesOnlyKnownProperties() {
        List<String> allViolations = new ArrayList<>();
        for (File file : exampleFiles) {
            Model dataModel = RdfUtils.parseTurtle(file);
            ValidationResult result = RdfUtils.checkVocabularyUsage(dataModel, completeOntology);
            JavaConverters.seqAsJavaList(result.messages()).forEach(m -> {
                String entry = file.getName() + ": " + m;
                LOG.warn("❌ [VOCAB ERROR] {}", entry);
                allViolations.add(entry);
            });
        }
        assertTrue(allViolations.isEmpty(),
                "Unknown properties/classes:\n" + String.join("\n", allViolations));
    }

    // ── 2. Class and property disjointness after inference ────────────────────

    @Test
    void eachExampleRespectsDisjointness() {
        List<String> allViolations = new ArrayList<>();
        for (File file : exampleFiles) {
            Model inferred = inferTriples(RdfUtils.parseTurtle(file));
            JavaConverters.seqAsJavaList(
                RdfUtils.checkClassDisjointness(inferred, classDisjointSubset).messages()
            ).forEach(v -> {
                String entry = file.getName() + ": " + v;
                LOG.warn("❌ [DISJOINT] {}", entry);
                allViolations.add(entry);
            });
            JavaConverters.seqAsJavaList(
                RdfUtils.checkPropertyDisjointness(inferred, propertyDisjointSubset).messages()
            ).forEach(v -> {
                String entry = file.getName() + ": " + v;
                LOG.warn("❌ [PROP-DISJOINT] {}", entry);
                allViolations.add(entry);
            });
        }
        assertTrue(allViolations.isEmpty(),
                "Disjointness violations:\n" + String.join("\n", allViolations));
    }

    // ── 3. OWL validation on the inferred model ───────────────────────────────

    @Test
    void eachExamplePassesOwlValidation() {
        List<String> allViolations = new ArrayList<>();
        for (File file : exampleFiles) {
            Model inferred = inferTriples(RdfUtils.parseTurtle(file));
            ValidationResult result = RdfUtils.validateModel(inferred, owlReasonerWithSchema);
            JavaConverters.seqAsJavaList(result.messages()).forEach(m -> {
                String entry = file.getName() + ": " + m;
                LOG.warn("❌ [OWL] {}", entry);
                allViolations.add(entry);
            });
        }
        assertTrue(allViolations.isEmpty(),
                "OWL violations:\n" + String.join("\n", allViolations));
    }

    // ── 4. SHACL validation ───────────────────────────────────────────────────

    @Test
    void eachExamplePassesShaclValidation() {
        List<String> allViolations = new ArrayList<>();
        for (File file : exampleFiles) {
            Model dataModel = inferForShacl(RdfUtils.parseTurtle(file));
            ValidationReport report = ShaclValidator.validate(dataModel, generatedShaclShapes);
            if (!report.conforms()) {
                report.getEntries().forEach(e -> {
                    String entry = file.getName() + ": focusNode=" + e.focusNode()
                            + ", path=" + e.resultPath()
                            + ", message=" + e.message();
                    LOG.warn("❌ [SHACL] {}", entry);
                    allViolations.add(entry);
                });
            }
        }
        assertTrue(allViolations.isEmpty(),
                "SHACL violations:\n" + String.join("\n", allViolations));
    }

    // ── 5. Combined validation of all examples together ───────────────────────

    @Test
    void combinedExamplesPassAllValidations() {
        Model combined = ModelFactory.createDefaultModel();
        exampleFiles.forEach(f -> combined.add(RdfUtils.parseTurtle(f)));
        Model inferred = inferTriples(combined);

        // Vocabulary
        ValidationResult vocabResult = RdfUtils.checkVocabularyUsage(combined, completeOntology);
        List<String> vocabMsgs = JavaConverters.seqAsJavaList(vocabResult.messages());
        vocabMsgs.forEach(m -> LOG.warn("❌ [VOCAB] combined: {}", m));
        assertTrue(vocabResult.valid(),
                "Vocabulary violations in combined examples:\n" + String.join("\n", vocabMsgs));

        // Class disjointness
        List<String> disjointViolations = JavaConverters.seqAsJavaList(
                RdfUtils.checkClassDisjointness(inferred, classDisjointSubset).messages());
        disjointViolations.forEach(v -> LOG.warn("❌ [DISJOINT] combined: {}", v));
        assertTrue(disjointViolations.isEmpty(),
                "Disjointness violations in combined examples:\n" + String.join("\n", disjointViolations));

        // Property disjointness
        List<String> propDisjointViolations = JavaConverters.seqAsJavaList(
                RdfUtils.checkPropertyDisjointness(inferred, propertyDisjointSubset).messages());
        propDisjointViolations.forEach(v -> LOG.warn("❌ [PROP-DISJOINT] combined: {}", v));
        assertTrue(propDisjointViolations.isEmpty(),
                "Property disjointness violations in combined examples:\n"
                        + String.join("\n", propDisjointViolations));

        // OWL
        ValidationResult owlResult = RdfUtils.validateModel(inferred, owlReasonerWithSchema);
        List<String> owlMsgs = JavaConverters.seqAsJavaList(owlResult.messages());
        owlMsgs.forEach(m -> LOG.warn("❌ [OWL] combined: {}", m));
        assertTrue(owlResult.valid(),
                "OWL violations in combined examples:\n" + String.join("\n", owlMsgs));

        // SHACL
        ValidationReport shaclReport = ShaclValidator.validate(inferForShacl(combined), generatedShaclShapes);
        ShaclValidator.printReport(shaclReport);
        assertTrue(shaclReport.conforms(), "SHACL violations in combined examples");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Model inferTriples(Model dataModel) {
        return RdfUtils.inferTriples(dataModel, completeOntology, ruleReasoner);
    }

    private Model inferForShacl(Model dataModel) {
        return RdfUtils.inferTriples(dataModel, completeOntology, shaclReasoner);
    }

    private List<File> javaFiles(String classpathDir) throws URISyntaxException {
        File dir = new File(getClass().getClassLoader().getResource(classpathDir).toURI());
        return JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(dir));
    }
}
