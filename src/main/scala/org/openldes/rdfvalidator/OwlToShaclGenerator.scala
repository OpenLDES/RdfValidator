package org.openldes.rdfvalidator

import org.apache.jena.rdf.model._
import org.apache.jena.vocabulary.{OWL, RDF, RDFS}

import scala.jdk.CollectionConverters._

/**
 * Internal representation of a SHACL property shape, used for deduplication.
 *
 * @param path      the URI of the property path
 * @param valueKind the value-type constraint derived from the OWL restriction
 * @param minCount  optional minimum cardinality
 * @param maxCount  optional maximum cardinality
 */
private case class PropertyShapeSignature(
                                           path: String,
                                           valueKind: ValueKind,
                                           minCount: Option[Int],
                                           maxCount: Option[Int]
                                         )

/**
 * Represents the type constraint associated with a SHACL property shape.
 *
 * Either a single class or datatype URI ([[SingleValue]]), or a union of
 * several class/datatype URIs ([[OrValue]]).
 */
private sealed trait ValueKind

/**
 * A single class or datatype constraint.
 *
 * @param uri        the URI of the class or datatype
 * @param isDatatype `true` if the URI denotes an XML Schema datatype
 */
private case class SingleValue(uri: String, isDatatype: Boolean) extends ValueKind

/**
 * A union of class or datatype constraints derived from `owl:unionOf`.
 *
 * @param members list of `(URI, isDatatype)` pairs, sorted by URI for stable comparison
 */
private case class OrValue(members: List[(String, Boolean)]) extends ValueKind

/**
 * Generates SHACL shapes from OWL class restrictions.
 *
 * For every `owl:Class` found in an ontology model, a `sh:NodeShape` is created
 * that targets that class.  Each `rdfs:subClassOf` restriction on the class is
 * translated into a `sh:PropertyShape`, capturing value-type constraints
 * (`sh:class`, `sh:datatype`, `sh:or`) and cardinality constraints
 * (`sh:minCount`, `sh:maxCount`).  Duplicate property shapes (same path and
 * constraint signature) are suppressed.
 */
object OwlToShaclGenerator {

  /** The SHACL namespace URI. */
  val SH = "http://www.w3.org/ns/shacl#"

  /**
   * Creates a SHACL property in the given model.
   *
   * @param local the local name of the SHACL property (e.g. `"class"`)
   * @param m     the model in which to create the property
   * @return the created [[Property]]
   */
  private def shaclProp(local: String, m: Model): Property =
    m.createProperty(SH + local)

  /**
   * Returns `true` if the resource is an XML Schema datatype URI.
   *
   * @param res the resource to inspect
   * @return `true` when the resource has a URI starting with the XSD namespace
   */
  private def isDatatype(res: Resource): Boolean =
    res.isURIResource &&
      res.getURI.startsWith("http://www.w3.org/2001/XMLSchema#")

  /**
   * Creates a SHACL path node for the given OWL property resource.
   *
   * If the property is a blank node representing an `owl:inverseOf` expression,
   * an anonymous SHACL inverse-path resource is returned; otherwise a plain
   * URI resource is returned.
   *
   * @param prop  the OWL property resource (may be anonymous for inverse properties)
   * @param shacl the SHACL model in which to create resources
   * @return the path node to be used as the value of `sh:path`
   */
  private def createPath(prop: Resource, shacl: Model): RDFNode = {
    if (prop.isAnon) {
      Option(prop.getPropertyResourceValue(OWL.inverseOf)) match {
        case Some(inv) if inv.isURIResource =>
          val b = shacl.createResource()
          b.addProperty(shaclProp("inversePath", shacl), inv)
          return b
        case _ =>
      }
    }
    shacl.createResource(prop.getURI)
  }

  /**
   * Computes the [[PropertyShapeSignature]] for an OWL restriction resource.
   *
   * Returns `None` if the restriction does not specify a named `owl:onProperty`.
   *
   * @param restriction the OWL restriction resource to inspect
   * @return `Some(signature)` when the restriction can be represented as a
   *         property shape, or `None` otherwise
   */
  private def computeSignature(restriction: Resource): Option[PropertyShapeSignature] = {

    val onProp = restriction.getPropertyResourceValue(OWL.onProperty)
    if (onProp == null || !onProp.isURIResource) return None

    val path = onProp.getURI

    val some = Option(restriction.getPropertyResourceValue(OWL.someValuesFrom))
    val all  = Option(restriction.getPropertyResourceValue(OWL.allValuesFrom))

    val valueNode = some.orElse(all)

    val valueKind: ValueKind =
      valueNode match {
        case Some(v) if v.hasProperty(OWL.unionOf) =>
          val list =
            v.getPropertyResourceValue(OWL.unionOf)
              .as(classOf[RDFList])

          val members =
            list.iterator().asScala
              .collect { case r: Resource if r.isURIResource =>
                (r.getURI, isDatatype(r))
              }
              .toList
              .sortBy(_._1)

          OrValue(members)

        case Some(v) if v.isURIResource =>
          SingleValue(v.getURI, isDatatype(v))

        case _ =>
          SingleValue("__none__", false)
      }

    def intValue(p: Property): Option[Int] =
      Option(restriction.getProperty(p))
        .map(_.getObject)
        .collect { case l: Literal => l.getInt }

    val min =
      intValue(OWL.cardinality)
        .orElse(intValue(OWL.minCardinality))
        .orElse(
          if (restriction.hasProperty(OWL.someValuesFrom) &&
            !restriction.hasProperty(OWL.minCardinality) &&
            !restriction.hasProperty(OWL.cardinality))
            Some(1)
          else None
        )

    val max =
      intValue(OWL.cardinality)
        .orElse(intValue(OWL.maxCardinality))

    Some(PropertyShapeSignature(path, valueKind, min, max))
  }

  /**
   * Adds either `sh:class` or `sh:datatype` to a property shape resource,
   * depending on whether `value` is an XML Schema datatype.
   *
   * @param ps    the property shape resource to annotate
   * @param value the class or datatype resource
   * @param shacl the SHACL model
   */
  private def addClassOrDatatype(ps: Resource, value: Resource, shacl: Model): Unit = {
    if (isDatatype(value))
      ps.addProperty(shaclProp("datatype", shacl), value)
    else
      ps.addProperty(shaclProp("class", shacl), value)
  }

  /**
   * Adds `sh:minCount 1` to a property shape when the restriction uses
   * `owl:someValuesFrom` without an explicit minimum cardinality.
   *
   * @param restriction the OWL restriction resource
   * @param ps          the property shape resource to annotate
   * @param shacl       the SHACL model
   */
  private def addMinCountIfNeeded(restriction: Resource, ps: Resource, shacl: Model): Unit = {
    if (
      restriction.hasProperty(OWL.someValuesFrom) &&
        !restriction.hasProperty(OWL.minCardinality) &&
        !restriction.hasProperty(OWL.cardinality)
    ) {
      ps.addLiteral(shaclProp("minCount", shacl), 1)
    }
  }

  /**
   * Translates OWL cardinality facets on a restriction to SHACL cardinality constraints.
   *
   * Handles `owl:minCardinality`, `owl:maxCardinality`, and `owl:cardinality`
   * (which maps to both `sh:minCount` and `sh:maxCount`).
   *
   * @param restriction the OWL restriction resource
   * @param ps          the property shape resource to annotate
   * @param shacl       the SHACL model
   */
  private def addCardinality(restriction: Resource, ps: Resource, shacl: Model): Unit = {
    def intValue(p: Property): Option[Int] =
      Option(restriction.getProperty(p))
        .map(_.getObject)
        .collect { case l: Literal => l.getInt }

    intValue(OWL.minCardinality).foreach(ps.addLiteral(shaclProp("minCount", shacl), _))
    intValue(OWL.maxCardinality).foreach(ps.addLiteral(shaclProp("maxCount", shacl), _))

    intValue(OWL.cardinality).foreach { exact =>
      ps.addLiteral(shaclProp("minCount", shacl), exact)
      ps.addLiteral(shaclProp("maxCount", shacl), exact)
    }
  }

  /**
   * Builds an RDF list of anonymous SHACL property shapes representing a
   * union (`sh:or`) of class or datatype constraints.
   *
   * @param unionNode a resource that either has `owl:unionOf` or is itself an RDF list
   * @param shacl     the SHACL model in which to create resources
   * @return an RDF list node suitable for the value of `sh:or`
   */
  private def createOrList(unionNode: Resource, shacl: Model): RDFNode = {
    val list =
      Option(unionNode.getPropertyResourceValue(OWL.unionOf))
        .getOrElse(unionNode)
        .as(classOf[RDFList])

    val shapes = list.iterator().asScala.map { member =>
      val ps = shacl.createResource()
      addClassOrDatatype(ps, member.asResource(), shacl)
      ps
    }.toList

    shacl.createList(shapes.iterator.asJava)
  }

  /**
   * Translates a single OWL restriction into a `sh:PropertyShape` and attaches
   * it to the given node shape via `sh:property`.
   *
   * @param restriction the OWL restriction resource to translate
   * @param shacl       the SHACL model in which to create resources
   * @param nodeShape   the node shape resource to which the property shape is added
   */
  private def generatePropertyShape(
                                     restriction: Resource,
                                     shacl: Model,
                                     nodeShape: Resource
                                   ): Unit = {

    val onProp = restriction.getPropertyResourceValue(OWL.onProperty)
    if (onProp == null) return

    val ps = shacl.createResource()
    ps.addProperty(shaclProp("path", shacl), createPath(onProp, shacl))

    val some = restriction.getPropertyResourceValue(OWL.someValuesFrom)
    val all  = restriction.getPropertyResourceValue(OWL.allValuesFrom)

    if (some != null) {
      if (some.hasProperty(OWL.unionOf)) {
        ps.addProperty(shaclProp("or", shacl), createOrList(some, shacl))
      } else {
        addClassOrDatatype(ps, some, shacl)
      }
    }

    if (all != null) {
      if (all.hasProperty(OWL.unionOf)) {
        ps.addProperty(shaclProp("or", shacl), createOrList(all, shacl))
      } else {
        addClassOrDatatype(ps, all, shacl)
      }
    }

    addMinCountIfNeeded(restriction, ps, shacl)
    addCardinality(restriction, ps, shacl)

    nodeShape.addProperty(shaclProp("property", shacl), ps)
  }

  /**
   * Generates a `sh:NodeShape` for the given OWL class and adds it to the SHACL model.
   *
   * All `rdfs:subClassOf` restrictions on the class are iterated; duplicate
   * property-shape signatures are suppressed so that each unique constraint
   * appears at most once.
   *
   * @param cls      the OWL class resource (must be a URI resource)
   * @param ontology the source OWL ontology model
   * @param shacl    the SHACL model in which to create the node shape
   */
  private def generateNodeShape(cls: Resource, ontology: Model, shacl: Model): Unit = {

    val ns = shacl.createResource(cls.getURI + "Shape")
    ns.addProperty(RDF.`type`, shacl.createResource(SH + "NodeShape"))
    ns.addProperty(shaclProp("targetClass", shacl), cls)

    val seen = scala.collection.mutable.Set[PropertyShapeSignature]()

    ontology
      .listStatements(cls, RDFS.subClassOf, null)
      .asScala
      .map(_.getObject)
      .collect {
        case r: Resource if r.hasProperty(RDF.`type`, OWL.Restriction) => r
      }
      .foreach { restriction =>
        computeSignature(restriction).foreach { sig =>
          if (!seen.contains(sig)) {
            generatePropertyShape(restriction, shacl, ns)
            seen += sig
          }
        }
      }
  }

  /**
   * Generates a SHACL model from an OWL ontology model.
   *
   * Every `owl:Class` in the ontology yields a `sh:NodeShape` with property
   * shapes derived from the class's OWL restrictions.
   *
   * @param ontology the OWL ontology model to translate
   * @return a new SHACL model containing the generated node shapes
   */
  def generate(ontology: Model): Model = {
    val shacl = ModelFactory.createDefaultModel()

    shacl.setNsPrefix("sh", SH)
    shacl.setNsPrefix("owl", OWL.NS)
    shacl.setNsPrefix("rdfs", RDFS.getURI)

    ontology
      .listResourcesWithProperty(RDF.`type`, OWL.Class)
      .asScala
      .filter(_.isURIResource)
      .foreach(generateNodeShape(_, ontology, shacl))

    shacl
  }
}
