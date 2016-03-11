package fr.unice.i3s.morph.xr2rml.mongo.abstractquery

import org.apache.log4j.Logger
import com.hp.hpl.jena.graph.Triple
import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.MorphBaseResultRdfTerms
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.exception.MorphException
import es.upm.fi.dia.oeg.morph.base.query.MorphAbstractQuery
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryCondition
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryProjection
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTriplesMap
import es.upm.fi.dia.oeg.morph.r2rml.model.xR2RMLLogicalSource
import fr.unice.i3s.morph.xr2rml.mongo.engine.MorphMongoDataTranslator
import fr.unice.i3s.morph.xr2rml.mongo.engine.MorphMongoResultSet
import fr.unice.i3s.morph.xr2rml.mongo.MongoDBQuery
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNodeAnd
import es.upm.fi.dia.oeg.morph.base.query.GenericQuery
import es.upm.fi.dia.oeg.morph.base.querytranslator.ConditionType
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNode
import fr.unice.i3s.morph.xr2rml.mongo.querytranslator.JsonPathToMongoTranslator
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNodeCond
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNodeUnion
import fr.unice.i3s.morph.xr2rml.mongo.querytranslator.MorphMongoQueryTranslator

/**
 * Representation of the abstract atomic query as defined in https://hal.archives-ouvertes.fr/hal-01245883
 *
 * @param triples the triple pattern for which we create this atomic query. Empty in the case
 * of a a parent query in a referencing object map. This may contain several triples after optimization e.g. self-join elimination
 * @param boundTriplesMap the triples map, bound to the triple pattern, from which this query is derived
 * @param from consists of the triples map logical source
 * @param project set of xR2RML references that shall be projected in the target query, i.e. the references
 * needed to generate the RDF terms of the result triples
 * @param where set of conditions applied to xR2RML references, entailed by matching the triples map
 * with the triple pattern.
 */
class MorphAbstractAtomicQuery(

    val triples: Set[Triple],
    boundTriplesMap: Option[R2RMLTriplesMap],
    val from: xR2RMLLogicalSource,
    val project: Set[MorphBaseQueryProjection],
    val where: Set[MorphBaseQueryCondition])

        extends MorphAbstractQuery(boundTriplesMap) {

    val logger = Logger.getLogger(this.getClass().getName());

    override def toString = {
        val fromStr =
            if (from.docIterator.isDefined)
                from.getValue + ", Iterator: " + from.docIterator
            else
                from.getValue

        val tripleStr = if (triples.nonEmpty) " triple : " + triples + "\n " else ""
        val boundTMStr = if (boundTriplesMap.isDefined) " boundTM: " + boundTriplesMap.get.toString + "\n " else ""

        "{" + tripleStr + boundTMStr + 
            " from   : " + fromStr + "\n" +
            "  project: " + project + "\n" +
            "  where  : " + where + " }"
    }

    /**
     * Translate an atomic abstract query into one or several concrete queries whose results must be UNIONed.
     *
     * First, the atomic abstract query is translated into an abstract MongoDB query using the
     * JsonPathToMongoTranslator.trans() function.
     * Then, the abstract MongoDB query is translated into a set of concrete MongoDB queries
     * by function mongoAbstractQuerytoConcrete().
     *
     * The result is stored in attribute this.targetQuery.
     *
     * @param translator the query translator
     */
    override def translateAtomicAbstactQueriesToConcrete(translator: MorphBaseQueryTranslator): Unit = {

        // Select isNotNull and Equality conditions
        val whereConds = this.where.filter(c => c.condType == ConditionType.IsNotNull || c.condType == ConditionType.Equals)
        if (logger.isDebugEnabled()) logger.debug("Translating conditions:" + whereConds)

        // Generate one abstract MongoDB query for each isNotNull and Equality condition
        val mongAbsQs: Set[MongoQueryNode] = whereConds.map(cond => {
            // If there is an iterator, replace the heading "$" of the JSONPath reference with the iterator path
            val iter = this.from.docIterator
            val reference =
                if (iter.isDefined) cond.reference.replace("$", iter.get)
                else cond.reference

            // Translate the condition on a JSONPath reference into an abstract MongoDB query (a MongoQueryNode)
            cond.condType match {
                case ConditionType.IsNotNull =>
                    JsonPathToMongoTranslator.trans(reference, new MongoQueryNodeCond(ConditionType.IsNotNull, null))
                case ConditionType.Equals =>
                    JsonPathToMongoTranslator.trans(reference, new MongoQueryNodeCond(ConditionType.Equals, cond.eqValue))
                case _ => throw new MorphException("Unsupported condition type " + cond.condType)
            }
        })

        // If there are several queries (more than 1), encapsulate them under a top-level AND
        val mongAbsQ =
            if (mongAbsQs.size > 1) new MongoQueryNodeAnd(mongAbsQs.toList)
            else mongAbsQs.head
        if (logger.isDebugEnabled())
            logger.debug("Conditions translated to abstract MongoDB query:\n" + mongAbsQ)

        // Create the concrete query/queries from the set of abstract MongoDB queries
        val from = MongoDBQuery.parseQueryString(this.from.getValue, this.from.docIterator, true)
        val queries = translator.asInstanceOf[MorphMongoQueryTranslator].mongoAbstractQuerytoConcrete(from, this.project, mongAbsQ)

        // Generate one GenericQuery for each concrete MongoDB query and assign the result as the target query
        this.targetQuery = queries.map(q => new GenericQuery(Constants.DatabaseType.MongoDB, q, this.from.docIterator))
    }

    /**
     * Check if this atomic abstract queries has a target query properly initialized
     * i.e. targetQuery is not empty
     */
    override def isTargetQuerySet: Boolean = { !targetQuery.isEmpty }

    /**
     * Return the set of SPARQL variables projected in this abstract query
     */
    override def getVariables: Set[String] = {
        project.filter(_.as.isDefined).map(_.as.get)
    }

    /**
     * Execute the query and produce the RDF terms for each of the result documents
     * by applying the triples map bound to this query.
     * If targetQuery contains several queries their result is UNIONed.
     *
     * @param dataSourceReader the data source reader to query the database
     * @param dataTrans the data translator to create RDF terms
     * @return a list of MorphBaseResultRdfTerms instances, one for each result document
     * May return an empty result but NOT null.
     */
    override def generateRdfTerms(
        dataSourceReader: MorphBaseDataSourceReader,
        dataTrans: MorphBaseDataTranslator): List[MorphBaseResultRdfTerms] = {

        if (triples.isEmpty || !boundTriplesMap.isDefined) {
            val errMsg = "Atomic abstract query with either no associtaed triple pattern or no triples map bound to the triple pattern" +
                " => the query is a child or parent query of a RefObjectMap. Cannot call the generatedRdfTerms method."
            logger.error(errMsg)
            logger.error("Query: " + this.toString)
            throw new MorphException(errMsg)
        }

        triples.toList.flatMap(tp => {
            val subjectAsVariable =
                if (tp.getSubject.isVariable)
                    Some(tp.getSubject.toString)
                else None
            val predicateAsVariable =
                if (tp.getPredicate.isVariable)
                    Some(tp.getPredicate.toString)
                else None
            val objectAsVariable =
                if (tp.getObject.isVariable)
                    Some(tp.getObject.toString)
                else None

            val dataTranslator = dataTrans.asInstanceOf[MorphMongoDataTranslator]
            val tm = this.boundTriplesMap.get
            val sm = tm.subjectMap;
            val pom = tm.predicateObjectMaps.head
            val iter: Option[String] = tm.logicalSource.docIterator
            logger.info("Generating RDF terms under triples map " + tm.toString + " for atomic query: \n" + this.toStringConcrete);

            // Execute the queries of tagetQuery and make a UNION (flatMap) of all the results
            val resSets = this.targetQuery.map(query => dataSourceReader.executeQueryAndIterator(query, iter))
            val resultSet = resSets.flatMap(res => res.asInstanceOf[MorphMongoResultSet].resultSet)
            logger.info("Query returned " + resultSet.size + " results.")

            // Main loop: iterate and process each result document of the result set
            var i = 0;
            val terms = for (document <- resultSet) yield {
                try {
                    i = i + 1;
                    if (logger.isDebugEnabled()) logger.debug("Generating RDF terms for document " + i + "/" + resultSet.size + ": " + document)

                    //---- Create the subject resource
                    val subjects = dataTranslator.translateData(sm, document)
                    if (subjects == null) { throw new Exception("null value in the subject triple") }
                    if (logger.isTraceEnabled()) logger.trace("Document " + i + " subjects: " + subjects)

                    //---- Create the list of resources representing subject target graphs
                    val subjectGraphs = sm.graphMaps.flatMap(sgmElement => {
                        dataTranslator.translateData(sgmElement, document)
                    })
                    if (logger.isTraceEnabled()) logger.trace("Document " + i + " subject graphs: " + subjectGraphs)

                    // ----- Make a list of resources for the predicate map of the predicate-object map
                    val predicates = dataTranslator.translateData(pom.predicateMaps.head, document)
                    if (logger.isTraceEnabled()) logger.trace("Document " + i + " predicates: " + predicates)

                    // ------ Make a list of resources for the object map of the predicate-object map
                    val objects =
                        if (!pom.objectMaps.isEmpty)
                            dataTranslator.translateData(pom.objectMaps.head, document)
                        else List.empty
                    if (logger.isTraceEnabled()) logger.trace("Document " + i + " objects: " + objects)

                    // ----- Create the list of resources representing target graphs mentioned in the predicate-object map
                    val predicateObjectGraphs = pom.graphMaps.flatMap(pogmElement => {
                        dataTranslator.translateData(pogmElement, document)
                    });
                    if (logger.isTraceEnabled()) logger.trace("Document" + i + " predicate-object map graphs: " + predicateObjectGraphs)

                    // Result 
                    Some(new MorphBaseResultRdfTerms(
                        subjects, subjectAsVariable,
                        predicates, predicateAsVariable,
                        objects, objectAsVariable,
                        (subjectGraphs ++ predicateObjectGraphs).toList))
                } catch {
                    case e: MorphException => {
                        logger.error("Error while translating data of document " + i + ": " + e.getMessage);
                        e.printStackTrace()
                        None
                    }
                    case e: Exception => {
                        logger.error("Unexpected error while translating data of document " + i + ": " + e.getCause() + " - " + e.getMessage);
                        e.printStackTrace()
                        None
                    }
                }
            }
            terms.flatten // get rid of the None's (in case there was an exception)
        })
    }

    /**
     * An atomic query cannot be optimized. return self
     */
    override def optimizeQuery: MorphAbstractQuery = {
        this
    }

    /**
     * Merge this atomic abstract query with another one in order to perform self-join elimination.
     * This is allowed if and only if:
     * (i) they have the same from (the logical source),
     * (ii) they have at least one shared variable (on which the join is to be done),
     * (iii) the shared variables are projected as the same xR2RML reference(s) in both queries.
     *
     * Example 1: if Q1 and Q2 have the same logical source, and they have a shared variable ?x,
     * then they need to project the same reference as ?x: "$.fieldName AS ?x".
     * If we have "$.field1 AS ?x" in Q1 "$.field2 AS ?x" is Q2, then this is not a self-join,
     * on the contrary this is a legitimate join.
     *
     * Example 2:
     * If Q1 and Q2 have the same logical source, and
     * Q1 has projections "$.a AS ?x", "$.b AS ?x",
     * Q2 has projections "$.a AS ?x", "$.c AS ?x"
     * then this is a proper self join.
     * => for each shared variable ?x, there should be at least one common projection of ?x.
     *
     * Note that the same variable can be projected several times, e.g. when the same variable is used
     * several times in a triple pattern e.g.: ?x :predicate ?x.
     * Besides a projection can contain several references in case the variable is matched with a template
     * term map, e.g. if ?x is matched with "http://domain/{$.ref1}/{$.ref2.*}", then the projection is:
     * Set($.ref1, $.ref2.*) AS ?x => in that case, we must have the same projection in the second query
     * for the merge to be possible.
     *
     * @param right the right query of the join
     * @return an MorphAbstractAtomicQuery if the merge is possible, None otherwise
     */
    def mergeWithAbstractAtmoicQuery(right: MorphAbstractAtomicQuery): Option[MorphAbstractAtomicQuery] = {

        var result: Option[MorphAbstractAtomicQuery] = None
        val left = this
        var canMerge = (left.from == right.from)
        if (canMerge) {
            val sharedVars = left.getVariables.intersect(right.getVariables)
            canMerge = sharedVars.nonEmpty
            if (canMerge) {
                sharedVars.foreach(x => {
                    // Get the references corresponding to the variable ?x in each query.
                    // In a template there may be several references for one variable, e.g. ?x is matched with "http://domain/{$.ref1}/{$.ref2.*}"
                    // In that case, the merge is possible iif ?x is bound to Set($.ref1, $.ref2.*) in both queries
                    val leftRefs = left.project.filter(_.as.get == x).map(_.references)
                    val rightRefs = right.project.filter(_.as.get == x).map(_.references)
                    canMerge = canMerge && leftRefs.intersect(rightRefs).nonEmpty
                })

                if (canMerge) {
                    var mergedWhere = left.where ++ right.where
                    var mergedProj = left.project ++ right.project
                    result = Some(new MorphAbstractAtomicQuery(left.triples ++ right.triples, boundTriplesMap, from, mergedProj, mergedWhere))
                }
            }
        }
        result
    }
}