package org.openldes.rdfvalidator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ShaclValidatorTest {

    private static final Logger LOG = LoggerFactory.getLogger(ShaclValidatorTest.class);

    private static final String PERSON_SHAPE_TTL =
            "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n" +
            "@prefix ex:  <http://example.org/> .\n" +
            "ex:PersonShape\n" +
            "  a sh:NodeShape ;\n" +
            "  sh:targetClass ex:Person ;\n" +
            "  sh:property [\n" +
            "    sh:path    ex:name ;\n" +
            "    sh:minCount 1 ;\n" +
            "    sh:message \"A person must have a name.\" ;\n" +
            "  ] .";

    private Shapes personShapes;

    @BeforeAll
    void setUp() {
        personShapes = Shapes.parse(parseTTL(PERSON_SHAPE_TTL));
    }

    private static Model parseTTL(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m);
        return m;
    }

    // --- loadShapes ---

    @Test
    void loadShapes_parsesShaclFileFromClasspath() {
        String url = getClass().getClassLoader()
                .getResource("ontologies/shacl.ttl").toString();
        Shapes shapes = ShaclValidator.loadShapes(url);
        assertNotNull(shapes, "loadShapes must not return null for an existing SHACL file");
    }

    // --- validate ---

    @Test
    void validate_returnsConformingReportForValidData() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Alice a ex:Person ; ex:name \"Alice\" .\n");
        ValidationReport report = ShaclValidator.validate(data, personShapes);
        assertTrue(report.conforms(),
                "Valid data must produce a conforming SHACL report");
    }

    @Test
    void validate_returnsNonConformingReportWhenRequiredPropertyMissing() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Bob a ex:Person .\n");
        ValidationReport report = ShaclValidator.validate(data, personShapes);
        assertFalse(report.conforms(),
                "A missing ex:name must produce a non-conforming SHACL report");
    }

    @Test
    void validate_reportsViolationForEachOffendingNode() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> .\n" +
                "ex:Bob   a ex:Person .\n" +
                "ex:Carol a ex:Person .\n");
        ValidationReport report = ShaclValidator.validate(data, personShapes);
        assertFalse(report.conforms(), "Two persons without a name must produce violations");
        assertEquals(2, report.getEntries().size(),
                "There must be exactly 2 violations (one per person without a name)");
    }

    // --- printReport ---

    @Test
    void printReport_doesNotThrowForConformingReport() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> . ex:Alice a ex:Person ; ex:name \"Alice\" .");
        ValidationReport report = ShaclValidator.validate(data, personShapes);
        assertDoesNotThrow(() -> ShaclValidator.printReport(report),
                "printReport must not throw for a conforming report");
    }

    @Test
    void printReport_doesNotThrowForNonConformingReport() {
        Model data = parseTTL(
                "@prefix ex: <http://example.org/> . ex:Bob a ex:Person .");
        ValidationReport report = ShaclValidator.validate(data, personShapes);
        assertDoesNotThrow(() -> ShaclValidator.printReport(report),
                "printReport must not throw for a non-conforming report");
    }
}
