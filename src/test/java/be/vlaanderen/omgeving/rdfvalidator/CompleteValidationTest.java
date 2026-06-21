package be.vlaanderen.omgeving.rdfvalidator;

import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.File;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CompleteValidationTest {

    private static final Logger LOG = LoggerFactory.getLogger(CompleteValidationTest.class);

    private static final Property OWL_MEMBERS =
            ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#members");

    // Only owl:inverseOf and rdfs:subClassOf — no domain/range rules, to prevent
    // blank nodes from being typed as qudt:QuantityValue via rdfs:range.
    private static final String SHACL_INFERENCE_RULES =
            "[inversePropertyRule:  (?p owl:inverseOf ?q), (?x ?p ?y) -> (?y ?q ?x)]" +
            "[inversePropertyRule2: (?p owl:inverseOf ?q), (?x ?q ?y) -> (?y ?p ?x)]" +
            "[subClassRule: (?y rdfs:subClassOf ?z), (?x rdf:type ?y) -> (?x rdf:type ?z)]";

    private Model completeOntology;
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

        Model disjointSubset = OntologySubsets.extractDisjointSubset(completeOntology);

        Model shaclModel = OwlToShaclGenerator.generate(completeOntology);
        generatedShaclShapes = Shapes.parse(shaclModel);

        owlReasonerWithSchema = ReasonerRegistry.getOWLMiniReasoner().bindSchema(disjointSubset);

        String rulesUrl = getClass().getClassLoader()
                .getResource("rules/domain-range-subproperty.rules").toString();
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

    // ── 2. Class disjointness after inference ─────────────────────────────────

    @Test
    void eachExampleRespectsClassDisjointness() {
        List<String> allViolations = new ArrayList<>();
        for (File file : exampleFiles) {
            Model inferred = inferTriples(RdfUtils.parseTurtle(file));
            checkDisjointness(inferred, completeOntology).forEach(v -> {
                String entry = file.getName() + ": " + v;
                LOG.warn("❌ [DISJOINT] {}", entry);
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

        // Disjointness
        List<String> disjointViolations = checkDisjointness(inferred, completeOntology);
        disjointViolations.forEach(v -> LOG.warn("❌ [DISJOINT] combined: {}", v));
        assertTrue(disjointViolations.isEmpty(),
                "Disjointness violations in combined examples:\n" + String.join("\n", disjointViolations));

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

    private List<String> checkDisjointness(Model dataModel, Model ontology) {
        List<String> errors = new ArrayList<>();

        Set<List<String>> disjointPairs = new LinkedHashSet<>();

        // owl:AllDisjointClasses with owl:members
        ontology.listSubjectsWithProperty(RDF.type, OWL2.AllDisjointClasses)
                .forEachRemaining(allDisjoint -> {
                    Statement membersStmt = allDisjoint.getProperty(OWL_MEMBERS);
                    if (membersStmt == null) return;
                    List<Resource> members = membersStmt.getResource()
                            .as(RDFList.class).asJavaList().stream()
                            .filter(RDFNode::isResource)
                            .map(RDFNode::asResource)
                            .collect(Collectors.toList());
                    for (int i = 0; i < members.size(); i++)
                        for (int j = i + 1; j < members.size(); j++)
                            disjointPairs.add(Arrays.asList(
                                    members.get(i).getURI(),
                                    members.get(j).getURI()));
                });

        // Pairwise owl:disjointWith
        ontology.listStatements(null, OWL.disjointWith, (RDFNode) null)
                .forEachRemaining(stmt -> {
                    if (stmt.getSubject().isURIResource() && stmt.getObject().isURIResource()) {
                        String a = stmt.getSubject().getURI();
                        String b = stmt.getObject().asResource().getURI();
                        List<String> pair = Arrays.asList(a, b);
                        Collections.sort(pair);
                        disjointPairs.add(pair);
                    }
                });

        // Check for each pair whether any resource holds both types
        for (List<String> pair : disjointPairs) {
            Resource c1 = ontology.createResource(pair.get(0));
            Resource c2 = ontology.createResource(pair.get(1));
            Set<Resource> typed1 = dataModel.listSubjectsWithProperty(RDF.type, c1).toSet();
            Set<Resource> typed2 = dataModel.listSubjectsWithProperty(RDF.type, c2).toSet();
            typed1.retainAll(typed2);
            typed1.forEach(r -> errors.add(
                    (r.isURIResource() ? r.getURI() : r.toString())
                            + " is typed as both " + c1.getLocalName()
                            + " and " + c2.getLocalName() + " (disjoint)"));
        }

        return errors;
    }

    private List<File> javaFiles(String classpathDir) throws URISyntaxException {
        File dir = new File(getClass().getClassLoader().getResource(classpathDir).toURI());
        return JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(dir));
    }
}
