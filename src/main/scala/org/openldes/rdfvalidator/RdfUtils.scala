package org.openldes.rdfvalidator

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.jsonldjava.core.{JsonLdOptions, JsonLdProcessor}
import com.github.jsonldjava.utils.JsonUtils
import org.apache.jena.rdf.model.{Model, ModelFactory, RDFNode, ResourceFactory}
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner
import org.apache.jena.reasoner.Reasoner
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.vocabulary.{OWL, RDF}

import java.io._
import scala.collection.JavaConverters._
import scala.util.Using

/**
 * The outcome of an RDF validation check.
 *
 * @param valid    `true` if the validated model satisfies all constraints
 * @param messages human-readable descriptions of any constraint violations;
 *                 empty when `valid` is `true`
 */
case class ValidationResult(valid: Boolean, messages: Seq[String])

/**
 * Collection of utility methods for RDF parsing, inference, validation, and JSON-LD processing.
 *
 * All parsing methods use Apache Jena; JSON-LD framing uses the JSON-LD Java library.
 */
object RdfUtils {

  /** Jackson object mapper with Scala module registered, used for JSON-LD serialisation. */
  val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  /**
   * Parses a Turtle file into an in-memory Jena model.
   *
   * @param file the Turtle file to parse
   * @return a new [[Model]] populated with the triples from the file
   * @throws java.io.FileNotFoundException if `file` does not exist
   */
  def parseTurtle(file: File): Model = {
    val model = ModelFactory.createDefaultModel()
    Using.resource(new FileInputStream(file)) { stream =>
      RDFParser.create()
        .source(stream)
        .lang(Lang.TTL)
        .parse(model)
    }
    model
  }

  /**
   * Parses a JSON-LD file into an in-memory Jena model.
   *
   * @param file the JSON-LD file to parse
   * @return a new [[Model]] populated with the triples from the file
   * @throws java.io.FileNotFoundException if `file` does not exist
   */
  def parseJsonLd(file: File): Model = {
    val model = ModelFactory.createDefaultModel()
    Using.resource(new FileInputStream(file)) { stream =>
      RDFParser.create()
        .source(stream)
        .lang(Lang.JSONLD)
        .parse(model)
    }
    model
  }

  /**
   * Recursively lists all Turtle files (`*.ttl`) under a directory.
   *
   * @param dir the root directory to search
   * @return a flat list of all `.ttl` files found, in filesystem order
   */
  def listTurtleFiles(dir: File): List[File] =
    Option(dir.listFiles()).getOrElse(Array.empty).toList.flatMap {
      case d if d.isDirectory => listTurtleFiles(d)
      case f if f.getName.endsWith(".ttl") => List(f)
      case _ => Nil
    }

  /**
   * Applies a rule-based reasoner to a data model, using an ontology as the schema.
   *
   * The returned model contains all original triples from `dataModel` plus any
   * triples inferred by the reasoner.  Namespace prefixes from `ontologyModel`
   * are copied to the result.
   *
   * @param dataModel      the RDF data model to reason over
   * @param ontologyModel  the ontology model used as the reasoning schema
   * @param reasoner       the rule-based reasoner to apply
   * @return a new [[Model]] combining the original data with the inferred triples
   */
  def inferTriples(dataModel: Model, ontologyModel: Model, reasoner: GenericRuleReasoner): Model = {
    val reasonerWithSchema = reasoner.bindSchema(ontologyModel)
    val infModel = ModelFactory.createInfModel(reasonerWithSchema, dataModel)
    val result = ModelFactory.createDefaultModel()
    result.setNsPrefixes(ontologyModel)
    result.add(dataModel)
    result.add(infModel.getDeductionsModel)
    result
  }

  /**
   * Validates an RDF model using an OWL reasoner that has already been bound to a schema.
   *
   * @param model                 the RDF model to validate
   * @param owlReasonerWithSchema an OWL reasoner pre-bound to an ontology schema
   * @return a [[ValidationResult]] indicating whether the model is consistent,
   *         together with any violation descriptions
   */
  def validateModel(model: Model, owlReasonerWithSchema: Reasoner): ValidationResult = {
    val infModel = ModelFactory.createInfModel(owlReasonerWithSchema, model)
    val report = infModel.validate()
    val messages =
      if (report.isValid) Seq.empty
      else report.getReports.asScala.map(_.getDescription).toSeq
    ValidationResult(report.isValid, messages)
  }

  /**
   * Checks whether all predicates and `rdf:type` values in a model are declared in an ontology.
   *
   * Unknown predicates are reported as `"Unknown property: <uri>"` and unknown
   * class URIs as `"Unknown class: <uri>"`.  Duplicate messages are suppressed.
   *
   * @param model    the RDF data model to inspect
   * @param ontology the ontology model against which vocabulary is checked
   * @return a [[ValidationResult]] listing any undeclared predicates or classes
   */
  def checkVocabularyUsage(model: Model, ontology: Model): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    model.listStatements().asScala.foreach { stmt =>
      val predicate = stmt.getPredicate
      if (predicate.isURIResource) {
        val uri = predicate.getURI
        if (!ontology.containsResource(ontology.getProperty(uri)))
          errors += s"Unknown property: $uri"
      }
    }

    model.listStatements(null, RDF.`type`, null).asScala.foreach { stmt =>
      val obj = stmt.getObject
      if (obj.isURIResource) {
        val uri = obj.asResource().getURI
        if (!ontology.containsResource(ontology.getResource(uri)))
          errors += s"Unknown class: $uri"
      }
    }

    ValidationResult(errors.isEmpty, errors.distinct.toList)
  }

  /**
   * Checks whether any resource in `model` is simultaneously typed as two disjoint classes,
   * using the pairwise `owl:disjointWith` axioms in `classDisjointSubset`.
   *
   * Obtain the subset via `OntologySubsets.extractClassDisjointSubset`, which normalises
   * both `owl:disjointWith` and `owl:AllDisjointClasses` into a flat, blank-node-free form.
   *
   * @param model               the RDF model to inspect (typically the inferred data model)
   * @param classDisjointSubset the subset containing only `owl:disjointWith` statements
   * @return a [[ValidationResult]] listing any class disjointness violations
   */
  def checkClassDisjointness(model: Model, classDisjointSubset: Model): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val seen   = scala.collection.mutable.Set[(String, String)]()

    classDisjointSubset.listStatements(null, OWL.disjointWith, null: RDFNode).asScala
      .filter(s => s.getSubject.isURIResource && s.getObject.isURIResource)
      .foreach { stmt =>
        val c1Uri = stmt.getSubject.getURI
        val c2Uri = stmt.getObject.asResource().getURI
        val key   = if (c1Uri < c2Uri) (c1Uri, c2Uri) else (c2Uri, c1Uri)
        if (!seen.contains(key)) {
          seen += key
          val c1 = ResourceFactory.createResource(c1Uri)
          val c2 = ResourceFactory.createResource(c2Uri)
          val typed1 = model.listSubjectsWithProperty(RDF.`type`, c1).asScala.toSet
          val typed2 = model.listSubjectsWithProperty(RDF.`type`, c2).asScala.toSet
          (typed1 intersect typed2).foreach { r =>
            val subj = if (r.isURIResource) r.getURI else r.toString
            errors += s"$subj is typed as both <$c1Uri> and <$c2Uri> (disjoint classes)"
          }
        }
      }

    ValidationResult(errors.isEmpty, errors.toList)
  }

  /**
   * Checks whether any subject in `model` uses two `owl:propertyDisjointWith` properties
   * with the same value, using the axioms in `propertyDisjointSubset`.
   *
   * Obtain the subset via `OntologySubsets.extractPropertyDisjointSubset`.
   *
   * @param model                  the RDF model to inspect (typically the inferred data model)
   * @param propertyDisjointSubset the subset containing only `owl:propertyDisjointWith` statements
   * @return a [[ValidationResult]] listing any property disjointness violations
   */
  def checkPropertyDisjointness(model: Model, propertyDisjointSubset: Model): ValidationResult = {
    val propertyDisjointWith = ResourceFactory.createProperty(OWL.NS + "propertyDisjointWith")
    val errors = scala.collection.mutable.ListBuffer[String]()
    val seen   = scala.collection.mutable.Set[(String, String)]()

    propertyDisjointSubset.listStatements(null, propertyDisjointWith, null: RDFNode).asScala
      .filter(s => s.getSubject.isURIResource && s.getObject.isURIResource)
      .foreach { stmt =>
        val p1Uri = stmt.getSubject.getURI
        val p2Uri = stmt.getObject.asResource().getURI
        val key   = if (p1Uri < p2Uri) (p1Uri, p2Uri) else (p2Uri, p1Uri)
        if (!seen.contains(key)) {
          seen += key
          val p1 = ResourceFactory.createProperty(p1Uri)
          val p2 = ResourceFactory.createProperty(p2Uri)
          model.listStatements(null, p1, null: RDFNode).asScala.foreach { s1 =>
            if (model.contains(s1.getSubject, p2, s1.getObject)) {
              val subj = if (s1.getSubject.isURIResource) s1.getSubject.getURI
                         else s1.getSubject.toString
              errors += s"$subj uses both <$p1Uri> and <$p2Uri> with the same value ${s1.getObject}"
            }
          }
        }
      }

    ValidationResult(errors.isEmpty, errors.toList)
  }

  /**
   * Serialises an RDF model to its JSON-LD representation as a [[JsonNode]].
   *
   * @param model the RDF model to serialise
   * @return `Some(jsonNode)` with the JSON-LD tree, or `None` if the model is empty
   */
  def modelToJsonLd(model: Model): Option[JsonNode] = {
    if (model.isEmpty) return None
    val out = new ByteArrayOutputStream()
    model.write(out, "JSON-LD")
    val jsonString = out.toString("UTF-8")
    Some(mapper.readTree(jsonString))
  }

  /**
   * Applies a JSON-LD frame to a JSON-LD document to produce a structured output.
   *
   * @param jsonLd the JSON-LD document to frame
   * @param frame  the JSON-LD frame to apply
   * @return `Some(framedNode)` with the framed result, or `None` if framing fails
   */
  def frameJsonLd(jsonLd: JsonNode, frame: JsonNode): Option[JsonNode] = {
    val options = new JsonLdOptions()
    try {
      val jsonLdObj = JsonUtils.fromString(jsonLd.toString)
      val frameObj  = JsonUtils.fromString(frame.toString)
      val framed = JsonLdProcessor.frame(jsonLdObj, frameObj, options)
      Some(mapper.readTree(JsonUtils.toPrettyString(framed)))
    } catch {
      case _: Exception => None
    }
  }

  /**
   * Extracts the `@graph` array from a framed JSON-LD document.
   *
   * @param framed the framed JSON-LD document
   * @return `Some(graphNode)` if a non-null `@graph` array is present, otherwise `None`
   */
  def extractGraph(framed: JsonNode): Option[JsonNode] =
    Option(framed.get("@graph")).filter(_.isArray)

  /**
   * Loads a JSON-LD frame from a file.
   *
   * @param path the filesystem path to the JSON frame file (UTF-8 encoded)
   * @return the parsed frame as a [[JsonNode]]
   * @throws java.io.FileNotFoundException if `path` does not exist
   */
  def loadFrame(path: String): JsonNode = {
    val jsonString: String =
      Using.resource(scala.io.Source.fromFile(path, "UTF-8")) { source =>
        source.getLines().mkString
      }
    mapper.readTree(jsonString)
  }

  /**
   * Derives a JSON-LD frame from a Jena model so that framing produces a flat array of records.
   *
   * Every typed subject in the model appears as its own top-level record in the `@graph` array.
   * Links to other subjects are kept as `@id` references rather than embedded objects.
   * All URIs are written in full; no prefix declarations are emitted.
   *
   * @param model the RDF model to derive the frame from
   * @return a [[JsonNode]] representing the JSON-LD frame
   */
  def deriveFrame(model: Model): JsonNode = {
    val frame   = mapper.createObjectNode()
    val context = frame.putObject("@context")
    frame.put("@omitDefault", true)

    if (model.isEmpty) return frame

    val types = model.listStatements(null, RDF.`type`, null).asScala
      .filter(_.getObject.isURIResource)
      .map(_.getObject.asResource().getURI)
      .toSeq.distinct

    if (types.size == 1) {
      frame.put("@type", types.head)
    } else if (types.size > 1) {
      val arr = frame.putArray("@type")
      types.foreach(arr.add)
    }

    val rdfTypeUri   = RDF.`type`.getURI
    val xsdStringUri = "http://www.w3.org/2001/XMLSchema#string"

    val prefixMapping = PrefixMapping.Factory.create()
      .setNsPrefixes(PrefixMapping.Standard)
      .setNsPrefixes(model)

    def contextBase(uri: String): String = {
      val prop = model.createProperty(uri)
      Option(prefixMapping.getNsURIPrefix(prop.getNameSpace))
        .map(p => s"${p}_${prop.getLocalName}")
        .getOrElse(s"${prop.getLocalName}_${Integer.toHexString(Math.abs(uri.hashCode))}")
    }

    model.listStatements().asScala
      .filter(_.getPredicate.getURI != rdfTypeUri)
      .toSeq
      .groupBy(_.getPredicate.getURI)
      .foreach { case (uri, stmts) =>
        val localName  = model.createProperty(uri).getLocalName
        val objects    = stmts.map(_.getObject)
        val uriObjects = objects.filter(_.isURIResource)
        val literals   = objects.filter(_.isLiteral).map(_.asLiteral())
        val hasBNodes  = objects.exists(_.isAnon)

        if (literals.isEmpty && uriObjects.nonEmpty && !hasBNodes) {
          // URI-resource property: compact values to plain strings via @type: @id in context
          context.putObject(uri)
            .put("@id", uri)
            .put("@type", "@id")

          val propFrame = frame.putObject(uri)
          propFrame.put("@embed", "@never")
          propFrame.put("@omitDefault", true)
          propFrame.putNull("@default")

        } else if (literals.isEmpty && hasBNodes) {
          // Blank-node property: keep embedded, omit when absent
          frame.putObject(uri)
            .put("@omitDefault", true)
            .putNull("@default")

        } else if (literals.nonEmpty) {
          val languages = literals.map(_.getLanguage).filter(_.nonEmpty).distinct
          val datatypes = literals.filter(_.getLanguage.isEmpty)
            .flatMap(l => Option(l.getDatatypeURI)).distinct

          if (languages.nonEmpty && datatypes.isEmpty && uriObjects.isEmpty) {
            // Language-tagged: per-language aliases in context, full URI in frame.
            // Key = prefix_localName when a namespace prefix is registered,
            // localName_<uriHash> otherwise — guaranteed unique in both cases.
            val base = contextBase(uri)
            languages.foreach { lang =>
              context.putObject(s"${base}_$lang")
                .put("@id", uri)
                .put("@language", lang)
            }
            frame.putObject(uri)
              .put("@omitDefault", true)
              .putNull("@default")

          } else if (datatypes.size == 1 && languages.isEmpty && uriObjects.isEmpty
                     && datatypes.head != xsdStringUri) {
            context.putObject(uri)
              .put("@id", uri)
              .put("@type", datatypes.head)
            frame.putObject(uri)
              .put("@type", datatypes.head)
              .put("@omitDefault", true)
              .putNull("@default")

          } else {
            frame.putObject(uri)
              .put("@omitDefault", true)
              .putNull("@default")
          }

        }
      }

    frame
  }
}
