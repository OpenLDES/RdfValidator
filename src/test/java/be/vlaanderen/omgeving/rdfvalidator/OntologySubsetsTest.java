package be.vlaanderen.omgeving.rdfvalidator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OntologySubsetsTest {

    private static final Logger LOG = LoggerFactory.getLogger(OntologySubsetsTest.class);

    private static Model parseTTL(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m);
        return m;
    }

    // --- extractStructuralSubset ---

    @Test
    void extractStructuralSubset_includesRdfsSubClassOf() {
        Model ontology = parseTTL(
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Sensor rdfs:subClassOf ex:Device .\n");
        Model subset = OntologySubsets.extractStructuralSubset(ontology);
        assertTrue(subset.contains(null, RDFS.subClassOf, (RDFNode) null),
                "The structural subset must contain rdfs:subClassOf statements");
    }

    @Test
    void extractStructuralSubset_includesDomainAndRange() {
        Model ontology = parseTTL(
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:measures rdfs:domain ex:Sensor ; rdfs:range ex:Property .\n");
        Model subset = OntologySubsets.extractStructuralSubset(ontology);
        assertTrue(subset.contains(null, RDFS.domain, (RDFNode) null),
                "The structural subset must contain rdfs:domain statements");
        assertTrue(subset.contains(null, RDFS.range, (RDFNode) null),
                "The structural subset must contain rdfs:range statements");
    }

    @Test
    void extractStructuralSubset_excludesOwlDisjointWith() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Sensor owl:disjointWith ex:Actuator .\n" +
                "ex:Sensor rdfs:subClassOf ex:Device .\n");
        Model subset = OntologySubsets.extractStructuralSubset(ontology);
        assertFalse(subset.contains(null, OWL.disjointWith, (RDFNode) null),
                "The structural subset must not contain owl:disjointWith statements");
        assertTrue(subset.contains(null, RDFS.subClassOf, (RDFNode) null),
                "The structural subset must contain rdfs:subClassOf statements");
    }

    @Test
    void extractStructuralSubset_excludesSubClassOfWithAnonymousObject() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Sensor rdfs:subClassOf ex:Device .\n" +
                "ex:Sensor rdfs:subClassOf [\n" +
                "  a owl:Restriction ;\n" +
                "  owl:onProperty ex:observes ;\n" +
                "] .\n");
        Model subset = OntologySubsets.extractStructuralSubset(ontology);
        List<Statement> stmts = new ArrayList<>();
        subset.listStatements(null, RDFS.subClassOf, (RDFNode) null).forEachRemaining(stmts::add);
        assertEquals(1, stmts.size(),
                "Only the subClassOf to a named class should remain");
        assertEquals("http://example.org/Device",
                stmts.get(0).getObject().asResource().getURI(),
                "The object of the remaining subClassOf must be ex:Device");
    }

    // --- extractDisjointSubset ---

    @Test
    void extractDisjointSubset_includesOwlDisjointWith() {
        Model ontology = parseTTL(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix ex:  <http://example.org/> .\n" +
                "ex:Sensor owl:disjointWith ex:Actuator .\n");
        Model subset = OntologySubsets.extractDisjointSubset(ontology);
        assertTrue(subset.contains(null, OWL.disjointWith, (RDFNode) null),
                "The disjoint subset must contain owl:disjointWith statements");
    }

    @Test
    void extractDisjointSubset_excludesRdfsSubClassOf() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Sensor owl:disjointWith ex:Actuator .\n" +
                "ex:Sensor rdfs:subClassOf ex:Device .\n");
        Model subset = OntologySubsets.extractDisjointSubset(ontology);
        assertFalse(subset.contains(null, RDFS.subClassOf, (RDFNode) null),
                "The disjoint subset must not contain rdfs:subClassOf statements");
        assertTrue(subset.contains(null, OWL.disjointWith, (RDFNode) null),
                "The disjoint subset must contain owl:disjointWith statements");
    }
}
