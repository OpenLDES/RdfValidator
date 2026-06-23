package org.openldes.rdfvalidator

import org.apache.jena.rdf.model.Model
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.shacl.{Shapes, ValidationReport}
import org.slf4j.LoggerFactory

/**
 * Façade for validating RDF models against SHACL shapes using Apache Jena.
 *
 * Provides methods for loading a SHACL shapes graph, running validation, and
 * logging the resulting validation report.
 */
object ShaclValidator {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Loads and parses a SHACL shapes graph from a file.
   *
   * @param shaclFile the filesystem path or URL of the SHACL file (Turtle or any Jena-supported format)
   * @return the parsed [[Shapes]] graph ready for use in validation
   */
  def loadShapes(shaclFile: String): Shapes = {
    val shapesModel = RDFDataMgr.loadModel(shaclFile)
    Shapes.parse(shapesModel)
  }

  /**
   * Validates an RDF model against a set of SHACL shapes.
   *
   * @param model  the RDF data model to validate
   * @param shapes the SHACL shapes graph to validate against
   * @return a [[ValidationReport]] detailing any constraint violations
   */
  def validate(model: Model, shapes: Shapes): ValidationReport =
    org.apache.jena.shacl.ShaclValidator.get.validate(shapes, model.getGraph)

  /**
   * Logs the outcome of a SHACL validation report.
   *
   * Logs an info message when the model conforms, or a warning message for
   * each violation entry when it does not.
   *
   * @param report the validation report to log
   */
  def printReport(report: ValidationReport): Unit = {
    if (report.conforms()) {
      logger.info("✅ Model is conform SHACL")
    } else {
      logger.warn("❌ Model is NOT conform:")
      report.getEntries.forEach { e =>
        logger.warn(s"- FocusNode: ${e.focusNode}, Path: ${e.resultPath}, Message: ${e.message}")
      }
    }
  }
}
