package org.openldes.rdfvalidator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OwlToShaclGeneratorTest {

    private static final Logger LOG = LoggerFactory.getLogger(OwlToShaclGeneratorTest.class);

    private static final String SH = OwlToShaclGenerator.SH();

    private static Model parseTTL(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(ttl).lang(Lang.TTL).parse(m);
        return m;
    }

    // --- generate ---

    @Test
    void generate_returnsEmptyModelWhenOntologyHasNoOwlClasses() {
        Model shacl = OwlToShaclGenerator.generate(ModelFactory.createDefaultModel());
        assertTrue(shacl.isEmpty(),
                "An empty ontology must not produce any SHACL shapes");
    }

    @Test
    void generate_createsNodeShapeWithTargetClassForEachOwlClass() {
        Model ontology = parseTTL(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix ex:  <http://example.org/> .\n" +
                "ex:Sensor a owl:Class .\n");
        Model shacl = OwlToShaclGenerator.generate(ontology);
        Property targetClass = shacl.createProperty(SH + "targetClass");
        assertTrue(shacl.contains(null, targetClass, shacl.createResource("http://example.org/Sensor")),
                "There must be a NodeShape with sh:targetClass ex:Sensor");
    }

    @Test
    void generate_addsShMinCount1ForOwlSomeValuesFromRestriction() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Sensor a owl:Class ;\n" +
                "  rdfs:subClassOf [\n" +
                "    a owl:Restriction ;\n" +
                "    owl:onProperty ex:observes ;\n" +
                "    owl:someValuesFrom ex:Property ;\n" +
                "  ] .\n");
        Model shacl = OwlToShaclGenerator.generate(ontology);
        Property minCount = shacl.createProperty(SH + "minCount");
        List<Integer> values = new ArrayList<>();
        shacl.listStatements(null, minCount, (RDFNode) null)
                .forEachRemaining(stmt -> values.add(stmt.getObject().asLiteral().getInt()));
        assertTrue(values.contains(1),
                "owl:someValuesFrom must produce sh:minCount 1; found values: " + values);
    }

    @Test
    void generate_addsBothMinCountAndMaxCountForOwlCardinalityRestriction() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Observation a owl:Class ;\n" +
                "  rdfs:subClassOf [\n" +
                "    a owl:Restriction ;\n" +
                "    owl:onProperty ex:hasResult ;\n" +
                "    owl:cardinality 1 ;\n" +
                "  ] .\n");
        Model shacl = OwlToShaclGenerator.generate(ontology);
        Property minCount = shacl.createProperty(SH + "minCount");
        Property maxCount = shacl.createProperty(SH + "maxCount");
        assertTrue(shacl.contains(null, minCount, (RDFNode) null),
                "owl:cardinality must produce sh:minCount");
        assertTrue(shacl.contains(null, maxCount, (RDFNode) null),
                "owl:cardinality must produce sh:maxCount");
    }

    @Test
    void generate_addsShClassButNoMinCountForOwlAllValuesFromRestriction() {
        Model ontology = parseTTL(
                "@prefix owl:  <http://www.w3.org/2002/07/owl#> .\n" +
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                "@prefix ex:   <http://example.org/> .\n" +
                "ex:Observation a owl:Class ;\n" +
                "  rdfs:subClassOf [\n" +
                "    a owl:Restriction ;\n" +
                "    owl:onProperty ex:hasFeatureOfInterest ;\n" +
                "    owl:allValuesFrom ex:FeatureOfInterest ;\n" +
                "  ] .\n");
        Model shacl = OwlToShaclGenerator.generate(ontology);
        Property shClass  = shacl.createProperty(SH + "class");
        Property minCount = shacl.createProperty(SH + "minCount");
        assertTrue(shacl.contains(null, shClass, (RDFNode) null),
                "owl:allValuesFrom must produce sh:class");
        assertFalse(shacl.contains(null, minCount, (RDFNode) null),
                "owl:allValuesFrom must not produce sh:minCount");
    }
}
