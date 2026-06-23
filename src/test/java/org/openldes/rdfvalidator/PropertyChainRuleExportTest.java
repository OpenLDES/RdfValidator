package org.openldes.rdfvalidator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PropertyChainRuleExportTest {

    private static final Logger LOG = LoggerFactory.getLogger(PropertyChainRuleExportTest.class);

    private Model completeOntology;

    @BeforeAll
    void setUp() throws URISyntaxException {
        completeOntology = ModelFactory.createDefaultModel();
        for (File f : javaFiles("ontologies")) {
            LOG.info("Loading ontology: {}", f.getName());
            completeOntology.add(RdfUtils.parseTurtle(f));
        }
    }

    @Test
    void extractPropertyChainRules_writesRuleFileToTarget() throws IOException {
        List<Rule> rules = JavaConverters.seqAsJavaList(
                OntologySubsets.extractPropertyChainRules(completeOntology));

        Path outDir = Paths.get("target/generated-inferred");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("property-chain.rules");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outFile))) {
            writer.println("# Generated property chain rules");
            writer.println("# Source: owl:propertyChainAxiom statements from the loaded ontologies");
            writer.println();
            for (Rule rule : rules) {
                writer.println(rule.toString());
            }
        }

        LOG.info("Wrote {} property chain rule(s) to {}", rules.size(), outFile.toAbsolutePath());
        assertTrue(Files.exists(outFile), "Rule file must have been created");
        assertTrue(rules.size() > 0, "At least one property chain rule must be found in the ontologies");
    }

    private List<File> javaFiles(String classpathDir) throws URISyntaxException {
        File dir = new File(getClass().getClassLoader().getResource(classpathDir).toURI());
        return JavaConverters.seqAsJavaList(RdfUtils.listTurtleFiles(dir));
    }
}
