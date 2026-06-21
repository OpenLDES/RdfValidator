# RdfValidator



# rdf-validator

Scala library for validating RDF data against an OWL ontology, with support for SHACL shapes, rule-based inference, and JSON-LD framing.

## Modules

| Module | Description |
|---|---|
| `RdfUtils` | Loading Turtle files, RDF inference, vocabulary checking, JSON-LD serialisation and framing |
| `OwlToShaclGenerator` | Auto-generates SHACL NodeShapes and PropertyShapes from OWL restrictions |
| `ShaclValidator` | Validates an RDF model against SHACL shapes and reports violations |
| `OntologySubsets` | Extracts ontology subsets (structural relations, disjoint axioms, property chain rules) |

## Requirements

- Java 21+
- Scala 2.12
- Maven 3.x

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
import be.vlaanderen.omgeving.rdfvalidator._

val ontology   = RdfUtils.parseTurtle(new File("path/to/ontology.ttl"))
val shaclModel = OwlToShaclGenerator.generate(ontology)
```

### Validate RDF data with SHACL

```scala
val dataModel = RdfUtils.parseTurtle(new File("path/to/data.ttl"))
val shapes    = ShaclValidator.loadShapes("path/to/shapes.ttl")
val report    = ShaclValidator.validate(dataModel, shapes)
ShaclValidator.printReport(report)
```

### Check vocabulary conformance

```scala
val result = RdfUtils.checkVocabularyUsage(dataModel, ontology)
if (!result.valid) result.messages.foreach(println)
```

### Apply inference

The library ships with a bundled static rules file (`rules/domain-range-subproperty.rules`) that implements a subset of OWL 2 RL:

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
import scala.jdk.CollectionConverters._

val rulesUrl    = getClass.getClassLoader.getResource("rules/domain-range-subproperty.rules").toString
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

### JSON-LD framing

Frame a model using a hand-written frame file:

```scala
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
| URI resource | `{"@type": "<linked-type>", "@embed": "@never", "@omitDefault": true, "@default": null}` | `{"@id": "<uri>", "@type": "@id"}` → value compacts to plain string |
| Blank node | `{"@omitDefault": true, "@default": null}` | — (embedded as nested object) |
| Language-tagged literal (single lang) | `{"@omitDefault": true, "@default": null}` | `"<localname>_<lang>": {"@id": "<uri>", "@language": "<lang>"}` |
| Language-tagged literal (multiple langs) | `{"@omitDefault": true, "@default": null}` | one alias per language: `label_nl`, `label_en`, … |
| Typed literal (`xsd:dateTime`, …) | `{"@type": "<datatype>", "@omitDefault": true, "@default": null}` | `{"@id": "<uri>", "@type": "<datatype>"}` → value compacts to plain string |
| Plain string (`xsd:string`) | `{"@omitDefault": true, "@default": null}` | — |

Top-level frame properties:

- `"@omitDefault": true` — properties absent from a subject are omitted from its record (no `null` values)
- `"@type"` — all distinct `rdf:type` values found in the model; every typed subject appears as a root record
- For URI-resource properties the linked-resource type is inferred from the model and added to the frame property when unambiguous

## Dependencies

- [Apache Jena](https://jena.apache.org/) 4.10 — RDF, SHACL, SPARQL, inference
- [Eclipse RDF4J](https://rdf4j.org/) 4.2 — RDF model and Turtle/JSON-LD I/O
- [jsonld-java](https://github.com/jsonld-java/jsonld-java) 0.13 — JSON-LD processing
- [Jackson](https://github.com/FasterXML/jackson) 2.15 — JSON (de)serialisation
- [Logback](https://logback.qos.ch/) 1.4 — logging

## Licence

Copyright © Departement Omgeving, Vlaamse overheid.
