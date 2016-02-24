package fr.unice.i3s.morph.xr2rml.mongo.engine

import org.apache.log4j.Logger

import com.hp.hpl.jena.datatypes.xsd.XSDDatatype
import com.hp.hpl.jena.rdf.model.Literal
import com.hp.hpl.jena.rdf.model.RDFNode
import com.hp.hpl.jena.vocabulary.RDF

import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.GeneralUtility
import es.upm.fi.dia.oeg.morph.base.MorphProperties
import es.upm.fi.dia.oeg.morph.base.TemplateUtility
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.exception.MorphException
import es.upm.fi.dia.oeg.morph.base.materializer.MorphBaseMaterializer
import es.upm.fi.dia.oeg.morph.base.path.MixedSyntaxPath
import es.upm.fi.dia.oeg.morph.base.query.GenericQuery
import es.upm.fi.dia.oeg.morph.base.query.MorphAbstractQuery
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTermMap
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTriplesMap
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.MorphAbstractAtomicQuery
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.MorphAbstractQueryInnerJoinRef
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.MorphAbstractQueryUnion

class MorphMongoDataTranslator(
    md: R2RMLMappingDocument,
    materializer: MorphBaseMaterializer,
    unfolder: MorphMongoUnfolder,
    dataSourceReader: MorphBaseDataSourceReader,
    properties: MorphProperties)

        extends MorphBaseDataTranslator(md, materializer, unfolder, properties) {

    if (!dataSourceReader.connection.isMongoDB)
        throw new MorphException("Database connection type does not match MongoDB")

    /** Store already executed queries to avoid running them several times. The key of the hashmap is the query string itself. */
    private val executedQueries: scala.collection.mutable.Map[String, List[String]] = new scala.collection.mutable.HashMap

    /** Store already parsed queries to avoid creating the same GenericQuery object several times. The key of the hashmap is the triples map name */
    private var queries: scala.collection.mutable.Map[String, GenericQuery] = new scala.collection.mutable.HashMap

    override val logger = Logger.getLogger(this.getClass().getName());

    /**
     * Query the database and build triples from the result. For each document of the result set:
     * <ol>
     * <li>create a subject resource and an optional graph resource if the subject map contains a rr:graph/rr:graphMap property,</li>
     * <li>loop on each predicate-object map: create a list of resources for the predicates, a list of resources for the objects,
     * a list of resources from the subject map of a parent object map in case there are referencing object maps,
     * and a list of resources representing target graphs mentioned in the predicate-object map.</li>
     * <li>Finally combine all subject, graph, predicate and object resources to generate triples.</li>
     * </ol>
     *
     * @param tm the triples map for which to generate the triples
     */
    override def generateRDFTriples(tm: R2RMLTriplesMap): Unit = {

        logger.info("Starting translating triples map " + tm.toString + " into RDF triples...");
        val ls = tm.logicalSource;
        val sm = tm.subjectMap;
        val poms = tm.predicateObjectMaps;
        val query = this.unfolder.unfoldConceptMapping(tm)

        // Execute the query against the database and apply the iterator
        val childResultSet = dataSourceReader.executeQueryAndIterator(query, ls.docIterator).asInstanceOf[MorphMongoResultSet].resultSet.toList

        // Execute the queries of all the parent triples maps (in the join conditions) against the database 
        // and apply their iterators. There queries will serve in computing the joins.
        // This "cache" lives just the time of computing this triples map, therefore it is
        // different from the global cache executedQueries that lives along the computing of all triples maps
        // parentResultSets is a map whose key is the query map id (query string + iterator), and the value 
        // if a list of result documents.
        val parentResultSets: scala.collection.mutable.Map[String, List[String]] = new scala.collection.mutable.HashMap
        poms.foreach(pom => {
            pom.refObjectMaps.foreach(rom => {
                val parentTM = this.md.getParentTriplesMap(rom)
                val parentQuery = this.unfolder.unfoldConceptMapping(parentTM)
                val queryMapId = makeQueryMapId(parentQuery, parentTM.logicalSource.docIterator)
                if (!parentResultSets.contains(queryMapId)) {
                    val resultSet = dataSourceReader.executeQueryAndIterator(parentQuery, parentTM.logicalSource.docIterator).asInstanceOf[MorphMongoResultSet].resultSet
                    parentResultSets += (queryMapId -> resultSet.toList)
                }
            })
        })

        // Main loop: iterate and process each result document of the result set
        var i = 0;
        for (document <- childResultSet) {
            i = i + 1;
            if (logger.isDebugEnabled()) logger.debug("Generating triples for document " + i + "/" + childResultSet.size + ": " + document)
            if (logger.isInfoEnabled()) System.out.print("Generating triples for document: " + i + "/" + childResultSet.size + "          \r")

            try {
                // Create the subject resource
                val subjects = this.translateData(sm, document)
                if (subjects == null) { throw new Exception("null value in the subject triple") }
                if (logger.isDebugEnabled()) logger.debug("Document " + i + " subjects: " + subjects)

                // Create the list of resources representing subject target graphs
                val subjectGraphs = sm.graphMaps.flatMap(sgmElement => {
                    val subjectGraphValue = this.translateData(sgmElement, document)
                    val graphMapTermType = sgmElement.inferTermType;
                    val subjectGraph = graphMapTermType match {
                        case Constants.R2RML_IRI_URI => { subjectGraphValue }
                        case _ => {
                            val errorMessage = "GraphMap's TermType is not valid: " + graphMapTermType;
                            logger.warn(errorMessage);
                            throw new MorphException(errorMessage);
                        }
                    }
                    subjectGraph
                }).toList;
                if (!subjectGraphs.isEmpty)
                    if (logger.isTraceEnabled()) logger.trace("Document " + i + " subject graphs: " + subjectGraphs)

                // Add subject resource to the JENA model with its class (rdf:type) and target graphs
                sm.classURIs.foreach(classURI => {
                    val classRes = this.materializer.model.createResource(classURI);
                    if (subjectGraphs == null || subjectGraphs.isEmpty) {
                        for (sub <- subjects) {
                            this.materializer.materializeQuad(sub, RDF.`type`, classRes, null);
                            this.materializer.outputStream.flush();
                        }
                    } else {
                        subjectGraphs.foreach(subjectGraph => {
                            for (sub <- subjects)
                                this.materializer.materializeQuad(sub, RDF.`type`, classRes, subjectGraph);
                        });
                    }
                });

                // Internal loop on each predicate-object map
                poms.foreach(pom => {
                    // ----- Make a list of resources for the predicate maps of this predicate-object map
                    val predicates = pom.predicateMaps.flatMap(predicateMap => { this.translateData(predicateMap, document) });
                    if (!predicates.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " predicates: " + predicates)

                    // ------ Make a list of resources for the object maps of this predicate-object map
                    val objects = pom.objectMaps.flatMap(objectMap => { this.translateData(objectMap, document) });
                    if (!objects.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " objects: " + objects)

                    // ----- For each RefObjectMap get the IRIs from the subject map of the parent triples map
                    val refObjects = pom.refObjectMaps.flatMap(refObjectMap => {

                        val parentTM = this.md.getParentTriplesMap(refObjectMap)

                        // Compute a list of subject IRIs for each of the join conditions, that we will intersect later
                        val parentSubjectsCandidates: Set[List[RDFNode]] = for (joinCond <- refObjectMap.joinConditions) yield {

                            // Evaluate the child reference on the current document (of the child triples map)
                            val childMsp = MixedSyntaxPath(joinCond.childRef, sm.refFormulaion)
                            val childValues: List[Object] = childMsp.evaluate(document)

                            // ---- Evaluate the parent reference on the results of the parent triples map logical source

                            // Create the mixed syntax path corresponding to the parent reference
                            val parentMsp = MixedSyntaxPath(joinCond.parentRef, parentTM.logicalSource.refFormulation)

                            // Get the results of the parent query
                            val queryMapId = makeQueryMapId(this.unfolder.unfoldConceptMapping(parentTM), parentTM.logicalSource.docIterator)
                            val parentRes = parentResultSets.get(queryMapId).get

                            // Evaluate the mixed syntax path on each parent query result. The result is stored as pairs:
                            // 		(JSON document, result of the evaluation of the parent reference on the JSON document)   
                            val parentValues = parentRes.map(res => (res, parentMsp.evaluate(res)))

                            // ---- Make the join between the child values and parent values
                            val parentSubjects = parentValues.flatMap(parentVal => {
                                // For each document returned by the parent triples map (named parent document),
                                // if at least one of the child values is in the current parent document values, 
                                // then generate an RDF term for the subject of the current parent document.
                                if (!childValues.intersect(parentVal._2).isEmpty) // parentVal._2 is the evaluation of the parent ref
                                    Some(this.translateData(parentTM.subjectMap, parentVal._1)) // parentVal._1 is the JSON document itself
                                else
                                    // There was no match: return an empty list so that the final intersection of candidate return nothing
                                    Some(List())
                            }).flatten
                            if (logger.isTraceEnabled()) logger.trace("Join parent candidates: " + joinCond.toString + ", result:" + parentSubjects)
                            parentSubjects
                        }

                        // There is a logical AND between several join conditions of the same RefObjectMap 
                        // => make the intersection between all subjects generated by all join conditions
                        val finalParentSubjects = GeneralUtility.intersectMultipleSets(parentSubjectsCandidates)
                        if (logger.isTraceEnabled()) logger.trace("Join parent subjects after intersection all joinConditions: " + finalParentSubjects)

                        // Optionally convert the result to an RDF collection or container
                        if (refObjectMap.isR2RMLTermType)
                            finalParentSubjects
                        else
                            createCollection(refObjectMap.termType.get, finalParentSubjects)
                    })
                    if (!refObjects.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " refObjects: " + refObjects)

                    // ----- Create the list of resources representing target graphs mentioned in the predicate-object map
                    val predicateObjectGraphs = pom.graphMaps.flatMap(pogmElement => {
                        val poGraphValue = this.translateData(pogmElement, document)
                        poGraphValue
                    }).toList;
                    if (!predicateObjectGraphs.isEmpty)
                        if (logger.isTraceEnabled()) logger.trace("Document" + i + " predicate-object map graphs: " + predicateObjectGraphs)

                    // ----- Finally, combine all the terms to generate triples in the target graphs or default graph
                    this.materializer.materializeQuads(subjects, predicates, objects, refObjects, subjectGraphs ++ predicateObjectGraphs)
                })
            } catch {
                case e: MorphException => {
                    logger.error("Error while translating data of document " + i + ": " + e.getMessage);
                    e.printStackTrace()
                }
                case e: Exception => {
                    logger.error("Unexpected error while translating data of document " + i + ": " + e.getCause() + " - " + e.getMessage);
                    e.printStackTrace()
                }
            }
        }
        if (logger.isDebugEnabled()) logger.debug(i + " instances retrieved.");
    }

    /**
     * Generate triples in the context of the query rewriting: run the child and optional parent queries,
     * and apply the triples map bound to the child query (GenericQuery.bondTriplesMap) to create RDF triples.
     *
     * This assumes that triples maps are normalized, i.e. (1) exactly one predicate-object map with exactly one
     * predicate map and one object map, (2) each rr:class property of the subject map was translated into an
     * equivalent normalized triples map.
     *
     * @param query an abstract query in which the targetQuery fields must have been set
     * @throws MorphException if one of the atomic abstract queries in this query has no target query
     */
    override def generateRDFTriples(query: MorphAbstractQuery): Unit = {
        if (!query.isTargetQuerySet)
            throw new MorphException("Target queries not set in " + query)

        val listTerms = query.generateRdfTerms(this.dataSourceReader, this)
        for (terms <- listTerms) {
            this.materializer.materializeQuads(terms.subjects, terms.predicates, terms.objects, List.empty, terms.graphs)
        }
    }

    /**
     * Apply a term map to a document of the result set, and generate a list of RDF terms:
     * for each element reference in the term map (reference or template), read values from the document,
     * then translate those values into RDF terms.
     */
    def translateData(termMap: R2RMLTermMap, jsonDoc: String): List[RDFNode] = {
        if (termMap == null) {
            val errorMessage = "TermMap is null";
            logger.error(errorMessage);
            throw new MorphException(errorMessage);
        }

        var datatype = termMap.datatype
        var languageTag = termMap.languageTag

        // Term type of the collection/container to generate, or None if this is not the case 
        var collecTermType: Option[String] = None

        // Term type of the RDF terms to generate from database values
        var memberTermType: String = Constants.R2RML_LITERAL_URI

        // In case of a collection/container, a nested term map should give the details of term type, datatype and language or the terms 
        if (R2RMLTermMap.isRdfCollectionTermType(termMap.inferTermType)) {
            collecTermType = Some(termMap.inferTermType)
            if (termMap.nestedTermMap.isDefined) { // a nested term type MUST be defined in a term map with collection/container term type
                memberTermType = termMap.nestedTermMap.get.inferTermType
                datatype = termMap.nestedTermMap.get.datatype
                languageTag = termMap.nestedTermMap.get.languageTag
            } else
                logger.warn("Term map with collection/container term type but no nested term map: " + termMap)
        } else {
            collecTermType = None
            memberTermType = termMap.inferTermType
        }

        var result: List[RDFNode] = termMap.termMapType match {

            // --- Constant-valued term map
            case Constants.MorphTermMapType.ConstantTermMap => {
                this.translateSingleValue(termMap.constantValue, collecTermType, memberTermType, datatype, languageTag)
            }

            // --- Reference-valued term map
            case Constants.MorphTermMapType.ReferenceTermMap => {

                // Evaluate the value against the mixed syntax path
                val msPath = termMap.getMixedSyntaxPaths()(0) // '(0)' because in a reference there is only one mixed syntax path
                val values: List[Object] = msPath.evaluate(jsonDoc)

                // Generate RDF terms from the values resulting from the evaluation
                this.translateMultipleValues(values, collecTermType, memberTermType, datatype, languageTag)
            }

            // --- Template-valued term map
            case Constants.MorphTermMapType.TemplateTermMap => {

                // For each group of the template, compute a list of replacement strings
                val msPaths = termMap.getMixedSyntaxPaths()
                val listReplace = for (i <- 0 to (msPaths.length - 1)) yield {

                    // Evaluate the raw value against the mixed-syntax path.
                    val valuesRaw: List[Object] = msPaths(i).evaluate(jsonDoc)
                    valuesRaw.filter(_ != null)
                }

                val replacements: List[List[Object]] = listReplace.toList
                if (logger.isTraceEnabled()) logger.trace("Template replacements: " + replacements)

                // Check if at least one of the replacements is not null.
                var isEmptyReplacements: Boolean = true
                for (repl <- listReplace) {
                    if (!repl.isEmpty)
                        isEmptyReplacements = false
                }

                // Replace "{...}" groups in the template string with corresponding values from the db
                if (isEmptyReplacements) {
                    if (logger.isTraceEnabled()) logger.trace("Template " + termMap.templateString + ": no values (or only null values) were read from from the DB.")
                    List()
                } else {
                    // Compute the list of template results by making all possible combinations of the replacement values
                    val tplResults = TemplateUtility.replaceTemplateGroups(termMap.templateString, replacements);
                    this.translateMultipleValues(tplResults, collecTermType, memberTermType, datatype, languageTag)
                }
            }

            case _ => { throw new MorphException("Invalid term map type " + termMap.termMapType) }
        }
        result
    }

    /**
     * Create a JENA literal resource with optional data type and language tag.
     * This method is overriden in the case of JSON to enable the mapping between JSON data types
     * and XSD data types
     *
     * @throws MorphException
     */
    override protected def createLiteral(value: Object, datatype: Option[String], language: Option[String]): Literal = {
        try {
            val encodedValue =
                if (value == null) // case when the database returned NULL
                    ""
                else
                    GeneralUtility.encodeLiteral(value.toString())

            val dataT: String = datatype.getOrElse(null)
            val valueConverted =
                if (dataT != null) {
                    if (dataT.equals(XSDDatatype.XSDdateTime.getURI().toString()))
                        this.translateDateTime(encodedValue);
                    else if (dataT.equals(XSDDatatype.XSDboolean.getURI().toString()))
                        this.translateBoolean(encodedValue);
                    else
                        encodedValue
                } else
                    encodedValue

            val result: Literal =
                if (language.isDefined)
                    this.materializer.model.createLiteral(valueConverted, language.get);
                else {
                    if (datatype.isDefined)
                        this.materializer.model.createTypedLiteral(valueConverted, datatype.get);
                    else {
                        val inferedDT = inferDataType(value)
                        if (inferedDT == null)
                            this.materializer.model.createLiteral(valueConverted);
                        else
                            this.materializer.model.createTypedLiteral(valueConverted, inferedDT);
                    }
                }

            result
        } catch {
            case e: Exception => {
                val msg = "Error translating object uri value : " + value
                logger.error(msg);
                throw new MorphException(msg, e)
            }
        }
    }

    /**
     * Defines the mapping from JSON data types to XSD data types
     */
    private def inferDataType(value: Object): String = {
        value match {
            case _: java.lang.Byte => XSDDatatype.XSDinteger.getURI()
            case _: java.lang.Short => XSDDatatype.XSDinteger.getURI()
            case _: java.lang.Integer => XSDDatatype.XSDinteger.getURI()
            case _: java.lang.Long => XSDDatatype.XSDinteger.getURI()

            case _: java.lang.Double => XSDDatatype.XSDdecimal.getURI()
            case _: java.lang.Float => XSDDatatype.XSDdecimal.getURI()

            case _: java.lang.Boolean => XSDDatatype.XSDboolean.getURI()

            case _: java.lang.Number => XSDDatatype.XSDdecimal.getURI()

            case _ => null
        }
    }

    private def makeQueryMapId(query: GenericQuery, iter: Option[String]): String = {
        if (iter.isDefined)
            query.concreteQuery.toString + ", Iterator: " + iter.get
        else
            query.concreteQuery.toString
    }
}