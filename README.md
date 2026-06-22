# rdf-validator

Scala library for validating RDF data against an OWL ontology, with support for SHACL shapes, rule-based inference, and JSON-LD framing.

## Modules

| Module | Description |
|---|---|
| `RdfUtils` | Loading Turtle and JSON-LD files, directory listing, RDF inference, OWL consistency checking, vocabulary checking, class and property disjointness checking, JSON-LD serialisation and framing |
| `OwlToShaclGenerator` | Auto-generates SHACL NodeShapes and PropertyShapes from OWL restrictions |
| `ShaclValidator` | Validates an RDF model against SHACL shapes and reports violations |
| `OntologySubsets` | Extracts ontology subsets (structural relations, class disjointness, property disjointness, property chain rules) |

## Requirements

- Java 21+
- Scala 2.12
- Maven 3.x

## Maven dependency

```xml
<dependency>
    <groupId>org.openldes</groupId>
    <artifactId>rdf-validator</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Build

```bash
mvn package
```

Run tests:

```bash
mvn test
```

## Usage

### Load an ontology and generate SHACL shapes

```scala
import org.openldes.rdfvalidator._

val ontology   = RdfUtils.parseTurtle(new File("path/to/ontology.ttl"))
val shaclModel = OwlToShaclGenerator.generate(ontology)
```

### Validate RDF data with SHACL

```scala
import org.openldes.rdfvalidator._

val dataModel = RdfUtils.parseTurtle(new File("path/to/data.ttl"))
val shapes    = ShaclValidator.loadShapes("path/to/shapes.ttl")
val report    = ShaclValidator.validate(dataModel, shapes)
ShaclValidator.printReport(report)
```

### Check vocabulary conformance

```scala
import org.openldes.rdfvalidator._

val result = RdfUtils.checkVocabularyUsage(dataModel, ontology)
if (!result.valid) result.messages.foreach(println)
```

### Check class disjointness

Detect resources that are simultaneously typed as two disjoint OWL classes.
Handles both pairwise `owl:disjointWith` and n-ary `owl:AllDisjointClasses`.

```scala
import org.openldes.rdfvalidator._

val classDisjointSubset = OntologySubsets.extractClassDisjointSubset(ontology)
val result = RdfUtils.checkClassDisjointness(inferredModel, classDisjointSubset)
if (!result.valid) result.messages.foreach(println)
```

### Check property disjointness

Detect subjects that use two `owl:propertyDisjointWith` properties with the same value.
The constraint model contains `owl:propertyDisjointWith` statements — provide your own or derive it from your ontology.

```scala
import org.openldes.rdfvalidator._

val propertyDisjointSubset = OntologySubsets.extractPropertyDisjointSubset(ontology)
val result = RdfUtils.checkPropertyDisjointness(inferredModel, propertyDisjointSubset)
if (!result.valid) result.messages.foreach(println)
```

### Apply inference

The library ships with a bundled static rules file (`rules/owl2-rl.rules`) that implements a subset of OWL 2 RL:

| Rule | Semantics |
|---|---|
| `rdfs:domain` / `rdfs:range` | Infer `rdf:type` from property use |
| `rdfs:subPropertyOf` | Assert the superproperty |
| `rdfs:subClassOf` | Assert the superclass |
| `owl:inverseOf` | Bidirectional inverse assertions |
| `owl:equivalentClass` | Bidirectional subclass (named resources only) |
| `owl:equivalentProperty` | Bidirectional subproperty (named resources only) |
| `owl:SymmetricProperty` | Symmetric property closure |
| `owl:TransitiveProperty` | Transitive property closure |

Combine these static rules with property chain rules derived from `owl:propertyChainAxiom` in the ontology:

```scala
import org.apache.jena.reasoner.rulesys.{GenericRuleReasoner, Rule}
import org.openldes.rdfvalidator._
import scala.collection.JavaConverters._

val rulesUrl    = getClass.getClassLoader.getResource("rules/owl2-rl.rules").toString
val staticRules = Rule.rulesFromURL(rulesUrl).asScala.toList
val chainRules  = OntologySubsets.extractPropertyChainRules(ontology).toList
val reasoner    = new GenericRuleReasoner((staticRules ++ chainRules).asJava)

// Pass the structural subset as the schema to avoid blank-node OWL restrictions
val schema        = OntologySubsets.extractStructuralSubset(ontology)
val enrichedModel = RdfUtils.inferTriples(dataModel, schema, reasoner)
```

`extractPropertyChainRules` converts each 2-element `owl:propertyChainAxiom` into a ground Jena rule of the form:

```
[propertyChain_0: (?x <p1> ?y), (?y <p2> ?z) -> (?x <p> ?z)]
```

Chains longer than 2 elements are skipped with a warning.

### Java interop

Because the library is written in Scala, Java callers need `JavaConverters` to bridge `Seq` to `List`:

```java
import scala.collection.JavaConverters;
import org.apache.jena.reasoner.rulesys.Rule;
import java.util.List;

List<Rule> staticRules = Rule.rulesFromURL(rulesUrl);
List<Rule> chainRules  = JavaConverters.seqAsJavaList(
        OntologySubsets.extractPropertyChainRules(ontology));

List<Rule> allRules = new ArrayList<>(staticRules);
allRules.addAll(chainRules);
GenericRuleReasoner reasoner = new GenericRuleReasoner(allRules);
```

### SKOS S14 — prefLabel uniqueness (bundled shape)

The library ships with a SHACL shape implementing SKOS integrity rule S14: a concept may have at most one `skos:prefLabel` per language tag.

```scala
import org.openldes.rdfvalidator._

val s14Url    = getClass.getClassLoader.getResource("shacl/skos-s14.ttl").toString
val s14Shapes = ShaclValidator.loadShapes(s14Url)
val report    = ShaclValidator.validate(dataModel, s14Shapes)
ShaclValidator.printReport(report)
```

### JSON-LD framing

Frame a model using a hand-written frame file:

```scala
import org.openldes.rdfvalidator._

val frame  = RdfUtils.loadFrame("path/to/frame.json")
val graph  = RdfUtils.modelToJsonLd(dataModel)
              .flatMap(RdfUtils.frameJsonLd(_, frame))
              .flatMap(RdfUtils.extractGraph)
```

#### Automatic frame derivation

`deriveFrame` inspects the model and generates a JSON-LD frame automatically, producing a flat `@graph` array where every typed subject is its own record:

```scala
val frame = RdfUtils.deriveFrame(dataModel)
val graph = RdfUtils.modelToJsonLd(dataModel)
             .flatMap(RdfUtils.frameJsonLd(_, frame))
             .flatMap(RdfUtils.extractGraph)
```

The generated frame and its `@context` are derived as follows:

| Value type | Frame property | `@context` entry |
|---|---|---|
| URI resource | `{"@embed": "@never", "@omitDefault": true, "@default": null}` | `{"@id": "<uri>", "@type": "@id"}` → value compacts to plain string |
| Blank node | `{"@omitDefault": true, "@default": null}` | — (embedded as nested object) |
| Language-tagged literal (single lang) | `{"@omitDefault": true, "@default": null}` | `"<localname>_<lang>": {"@id": "<uri>", "@language": "<lang>"}` |
| Language-tagged literal (multiple langs) | `{"@omitDefault": true, "@default": null}` | one alias per language: `label_nl`, `label_en`, … |
| Typed literal (`xsd:dateTime`, …) | `{"@type": "<datatype>", "@omitDefault": true, "@default": null}` | `{"@id": "<uri>", "@type": "<datatype>"}` → value compacts to plain string |
| Plain string (`xsd:string`) | `{"@omitDefault": true, "@default": null}` | — |

Top-level frame properties:

- `"@omitDefault": true` — properties absent from a subject are omitted from its record (no `null` values)
- `"@type"` — all distinct `rdf:type` values found in the model; every typed subject appears as a root record

## Dependencies

- [Apache Jena](https://jena.apache.org/) 4.10.0 — RDF, SHACL, SPARQL, inference
- [Eclipse RDF4J](https://rdf4j.org/) 4.2.1 — RDF model and Turtle/JSON-LD I/O
- [jsonld-java](https://github.com/jsonld-java/jsonld-java) 0.13.3 — JSON-LD processing
- [Jackson](https://github.com/FasterXML/jackson) 2.15.2 — JSON (de)serialisation
- [Logback](https://logback.qos.ch/) 1.4.12 — logging

## Licence

Copyright © Departement Omgeving, Vlaamse overheid.
