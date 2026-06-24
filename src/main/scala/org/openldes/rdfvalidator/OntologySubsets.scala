package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.{Model, ModelFactory, RDFList, ResourceFactory}
import org.apache.jena.reasoner.rulesys.Rule
import org.apache.jena.vocabulary.{OWL, OWL2, RDF, RDFS}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/**
 * Utility object for extracting targeted subsets from an OWL ontology model.
 *
 * Each extraction method copies the namespace prefix mappings from the source
 * model and skips any statement whose subject or object is a blank node,
 * keeping the result free of anonymous resources.
 */
object OntologySubsets {

  private val LOG: Logger = LoggerFactory.getLogger(getClass)

  private val OWL_MEMBERS            = ResourceFactory.createProperty(OWL.NS + "members")
  private val PROPERTY_DISJOINT_WITH = ResourceFactory.createProperty(OWL.NS + "propertyDisjointWith")

  /**
   * Extracts the structural subset of an ontology model.
   *
   * The returned model contains only statements with the following predicates:
   *  - `rdfs:subPropertyOf`
   *  - `rdfs:subClassOf`
   *  - `owl:inverseOf`
   *  - `owl:equivalentClass`
   *  - `owl:equivalentProperty`
   *  - `rdfs:domain`
   *  - `rdfs:range`
   *  - `rdf:type owl:TransitiveProperty`
   *  - `rdf:type owl:SymmetricProperty`
   *
   * Statements whose subject or object is a blank node are excluded. This prevents
   * OWL restriction blank nodes (e.g. `rdfs:subClassOf [owl:Restriction ...]`) from
   * entering the inference schema and generating meaningless `?x rdf:type _:b` triples.
   *
   * @param source the full OWL ontology model
   * @return a new model containing only the structural statements
   */
  def extractStructuralSubset(source: Model): Model = {
    val subset = ModelFactory.createDefaultModel()

    source.getNsPrefixMap.forEach { case (prefix, uri) =>
      subset.setNsPrefix(prefix, uri)
    }

    val structuralPredicates = Seq(
      RDFS.subPropertyOf,
      RDFS.subClassOf,
      OWL.inverseOf,
      OWL.equivalentClass,
      OWL.equivalentProperty
    )

    structuralPredicates.foreach { p =>
      source.listStatements(null, p, null).forEachRemaining { stmt =>
        if (!stmt.getSubject.isAnon && stmt.getObject.isResource && !stmt.getObject.asResource().isAnon) {
          subset.add(stmt)
        }
      }
    }

    Seq(RDFS.domain, RDFS.range).foreach { p =>
      source.listStatements(null, p, null).forEachRemaining { stmt =>
        val subject = stmt.getSubject
        val obj = stmt.getObject
        if (!subject.isAnon && obj.isResource && !obj.asResource().isAnon) {
          subset.add(stmt)
        }
      }
    }

    // rdf:type statements for owl:TransitiveProperty and owl:SymmetricProperty
    Seq(OWL.TransitiveProperty, OWL.SymmetricProperty).foreach { owlClass =>
      source.listStatements(null, RDF.`type`, owlClass).forEachRemaining { stmt =>
        if (!stmt.getSubject.isAnon) subset.add(stmt)
      }
    }

    subset
  }

  /**
   * Extracts a blank-node-free class-disjointness subset from an ontology model,
   * normalising both OWL disjointness forms into pairwise `owl:disjointWith` statements:
   *
   *  - `owl:AllDisjointClasses` + `owl:members` (n-ary): expanded into all pairwise
   *    `(members(i), members(j))` combinations; only named-resource members are included.
   *  - `owl:disjointWith` (pairwise): copied directly when both ends are named resources.
   *
   * The result contains only `owl:disjointWith` statements, making it suitable for
   * consumption by `RdfUtils.checkClassDisjointness`.
   *
   * @param source the full OWL ontology model
   * @return a new model containing only pairwise `owl:disjointWith` statements
   */
  def extractClassDisjointSubset(source: Model): Model = {
    val subset = ModelFactory.createDefaultModel()
    source.getNsPrefixMap.forEach { case (prefix, uri) => subset.setNsPrefix(prefix, uri) }

    source.listSubjectsWithProperty(RDF.`type`, OWL2.AllDisjointClasses).asScala.foreach { allDisjoint =>
      val membersStmt = allDisjoint.getProperty(OWL_MEMBERS)
      if (membersStmt != null && membersStmt.getObject.isResource) {
        try {
          val members = membersStmt.getObject.asResource().as(classOf[RDFList]).asJavaList().asScala
            .filter(_.isURIResource).map(_.asResource()).toIndexedSeq
          for (i <- members.indices; j <- (i + 1) until members.length)
            subset.add(members(i), OWL.disjointWith, members(j))
        } catch {
          case e: Exception =>
            LOG.warn(s"Could not expand owl:AllDisjointClasses <$allDisjoint>: ${e.getMessage}")
        }
      }
    }

    source.listStatements(null, OWL.disjointWith, null).forEachRemaining { stmt =>
      if (!stmt.getSubject.isAnon && stmt.getObject.isResource && !stmt.getObject.asResource().isAnon)
        subset.add(stmt)
    }

    subset
  }

  /**
   * Extracts the property-disjointness subset of an ontology model.
   *
   * The returned model contains only `owl:propertyDisjointWith` statements where
   * neither the subject nor the object is a blank node.
   *
   * @param source the full OWL ontology model
   * @return a new model containing only `owl:propertyDisjointWith` statements
   */
  def extractPropertyDisjointSubset(source: Model): Model = {
    val subset = ModelFactory.createDefaultModel()
    source.getNsPrefixMap.forEach { case (prefix, uri) => subset.setNsPrefix(prefix, uri) }

    source.listStatements(null, PROPERTY_DISJOINT_WITH, null).forEachRemaining { stmt =>
      if (!stmt.getSubject.isAnon && stmt.getObject.isResource && !stmt.getObject.asResource().isAnon)
        subset.add(stmt)
    }

    subset
  }

  /**
   * Transpiles `owl:propertyChainAxiom` statements from an ontology into concrete
   * Jena Generic Rules. Chains of two or more properties are supported.
   *
   * The generated rules are ground (contain actual URIs, not list traversal), which
   * avoids the need to include the blank-node RDF list structure in the inference schema.
   * Combine the result with the static rules from `owl2-rl.rules` and
   * pass the merged list to the `GenericRuleReasoner`.
   *
   * @param ontology the full OWL ontology model
   * @return a sequence of Jena rules, one per property chain
   */
  def extractPropertyChainRules(ontology: Model): Seq[Rule] = {
    val rules = scala.collection.mutable.ArrayBuffer[Rule]()
    var index = 0

    ontology.listStatements(null, OWL2.propertyChainAxiom, null).forEachRemaining { stmt =>
      val property = stmt.getSubject
      val listNode  = stmt.getObject

      if (property.isURIResource && listNode.isResource) {
        try {
          val members = listNode.asResource().as(classOf[RDFList]).asJavaList().asScala
          if (members.size >= 2 && members.forall(_.isURIResource)) {
            val props   = members.map(_.asResource().getURI)
            val p       = property.getURI
            val n       = props.size
            val vars    = (0 to n).map(i => s"?v$i")
            val body    = props.zipWithIndex.map { case (prop, i) =>
              s"(${vars(i)} <$prop> ${vars(i + 1)})"
            }.mkString(", ")
            val ruleStr = s"[propertyChain_$index: $body -> (${vars(0)} <$p> ${vars(n)})]"
            rules += Rule.parseRule(ruleStr)
            index += 1
          } else if (members.size < 2) {
            LOG.warn(s"Skipping property chain for <${property.getURI}>: chain length ${members.size} (minimum 2 required)")
          }
        } catch {
          case e: Exception =>
            LOG.warn(s"Could not parse property chain for <${property.getURI}>: ${e.getMessage}")
        }
      }
    }

    rules.toSeq
  }
}
