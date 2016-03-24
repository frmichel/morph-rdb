package fr.unice.i3s.morph.xr2rml.mongo.abstractquery

import org.apache.log4j.Logger
import es.upm.fi.dia.oeg.morph.base.MorphBaseResultRdfTerms
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.query.AbstractQuery
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTriplesMap
import fr.unice.i3s.morph.xr2rml.mongo.engine.MorphMongoDataTranslator
import fr.unice.i3s.morph.xr2rml.mongo.querytranslator.MorphMongoQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.base.exception.MorphException
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryOptimizer
import fr.unice.i3s.morph.xr2rml.mongo.MongoDBQuery

/**
 * Representation of the INNER JOIN abstract query generated from the join of several basic graph patterns.
 * It is not allowed to create an inner join instance with nested inner joins.
 *
 * Use AbstractQueryInnerJoin.apply(left, right) to build instances: instead of creating nested
 * inner joins, this flattens the members of the left and right queries.
 *
 * @param left the query representing the left basic graph pattern of the join
 * @param right the query representing the right basic graph pattern of the join
 */
class AbstractQueryInnerJoin(
    val members: List[AbstractQuery])
        extends AbstractQuery(Set.empty) {

    val logger = Logger.getLogger(this.getClass().getName());

    if (members.size < 2)
        throw new MorphException("Attempt to create an inner join with less than 2 members: " + members)

    val nestedJoins = members.filter(_.isInstanceOf[AbstractQueryInnerJoin])
    if (nestedJoins.nonEmpty)
        throw new MorphException("Attempt to create nested inner joins: " + members)

    override def equals(a: Any): Boolean = {
        a.isInstanceOf[AbstractQueryUnion] &&
            this.members == a.asInstanceOf[AbstractQueryUnion].members
    }

    override def toString = {
        val subq =
            if (members.size >= 3) new AbstractQueryInnerJoin(members.tail)
            else members.tail.head

        "[" + members.head.toString + "\n" +
            "] INNER JOIN [\n" +
            subq.toString + "\n" +
            "] ON " + AbstractQuery.getSharedVariables(members.head, subq)
    }

    override def toStringConcrete: String = {
        val subq =
            if (members.size >= 3) new AbstractQueryInnerJoin(members.tail)
            else members.tail.head

        "[" + members.head.toStringConcrete + "\n" +
            "] INNER JOIN [\n" +
            subq.toStringConcrete + "\n" +
            "] ON " + AbstractQuery.getSharedVariables(members.head, subq)
    }

    /**
     * Translate all atomic abstract queries of this abstract query into concrete queries.
     * @param translator the query translator
     */
    override def translateAtomicAbstactQueriesToConcrete(translator: MorphBaseQueryTranslator): Unit = {
        for (q <- members)
            q.translateAtomicAbstactQueriesToConcrete(translator)
    }

    /**
     * Check if atomic abstract queries within this query have a target query properly initialized
     * i.e. targetQuery is not empty
     */
    override def isTargetQuerySet: Boolean = {
        var res: Boolean = false
        if (members.nonEmpty)
            res = members.head.isTargetQuerySet
        for (q <- members)
            res = res && q.isTargetQuerySet
        res
    }

    /**
     * Return the list of SPARQL variables projected in this abstract query
     */
    override def getVariables: Set[String] = {
        members.flatMap(m => m.getVariables).toSet
    }

    /**
     * Execute the left and right queries, generate the RDF terms for each of the result documents,
     * then make a JOIN of all the results
     *
     * @param dataSourceReader the data source reader to query the database
     * @param dataTrans the data translator to create RDF terms
     * @return a list of MorphBaseResultRdfTerms instances, one for each result document
     * May return an empty result but NOT null.
     * @throws es.upm.fi.dia.oeg.morph.base.exception.MorphException if the triples map bound to the query has no referencing object map
     */
    override def generateRdfTerms(
        dataSourceReader: MorphBaseDataSourceReader,
        dataTranslator: MorphBaseDataTranslator): Set[MorphBaseResultRdfTerms] = {

        logger.info("Generating RDF triples from the inner join query:\n" + this.toStringConcrete)

        var joinResult = Map[String, MorphBaseResultRdfTerms]()

        // First, generate the triples for both left and right graph patterns of the join
        val subq =
            if (members.size >= 3) new AbstractQueryInnerJoin(members.tail)
            else members.tail.head
        val leftTriples = members.head.generateRdfTerms(dataSourceReader, dataTranslator)
        val rightTriples = subq.generateRdfTerms(dataSourceReader, dataTranslator)
        var nonJoinedLeft = leftTriples
        var nonJoinedRight = rightTriples

        if (logger.isDebugEnabled)
            logger.debug("Inner joining " + leftTriples.size + " left triples with " + rightTriples.size + " right triples.")
        if (logger.isTraceEnabled) {
            logger.trace("Left triples:\n" + leftTriples.mkString("\n"))
            logger.trace("Right triples:\n" + rightTriples.mkString("\n"))
        }

        val sharedVars = AbstractQuery.getSharedVariables(members.head, subq)
        if (sharedVars.isEmpty) {
            val res = leftTriples ++ rightTriples // no filtering if no common variable
            logger.info("Inner join on empty set of variables computed " + res.size + " triples.")
            res
        } else {
            // For each variable x shared by both graph patterns, select the left and right triples
            // in which at least one term is bound to x, then join the documents on these terms.
            for (x <- sharedVars) {

                val leftTripleX = leftTriples.filter(_.hasVariable(x))
                val rightTripleX = rightTriples.filter(_.hasVariable(x))

                for (leftTriple <- leftTripleX) {
                    val leftTerm = leftTriple.getTermsForVariable(x)
                    for (rightTriple <- rightTripleX) {
                        val rightTerm = rightTriple.getTermsForVariable(x)
                        if (!leftTerm.intersect(rightTerm).isEmpty) {
                            // If there is a match, keep the two MorphBaseResultRdfTerms instances 
                            if (!joinResult.contains(leftTriple.getId))
                                joinResult += (leftTriple.getId -> leftTriple)
                            if (!joinResult.contains(rightTriple.getId))
                                joinResult += (rightTriple.getId -> rightTriple)
                        }
                    }
                }

                // All left and right triples that do not contain any of the shared variables are kept separatly
                nonJoinedLeft = nonJoinedLeft.filter(!_.hasVariable(x))
                nonJoinedRight = nonJoinedRight.filter(!_.hasVariable(x))
            }

            val res = joinResult.values.toSet
            val resNonJoined = nonJoinedLeft ++ nonJoinedRight
            logger.info("Inner join computed " + res.size + " triples + " + resNonJoined.size + " triples with no shared variable.")
            res ++ resNonJoined
        }
    }

    /**
     * Try to merge atomic queries among the members of the inner join
     *
     * @note in this function we use the slice method:
     * list.slice(start, end) : from start (included) until end (excluded), i.e. slice(n,n) returns an empty list
     */
    override def optimizeQuery(optimizer: MorphBaseQueryOptimizer): AbstractQuery = {

        if (!optimizer.selfJoinElimination) return this

        if (members.size == 1) { // security test but abnormal case, should never happen
            logger.warn("Unexpected case: inner join with only one member: " + this.toString)
            return members.head
        }

        if (logger.isDebugEnabled)
            logger.debug("\n------------------ Optimizing query ------------------\n" + this)

        // First, optimize all members individually
        var membersV = members.map(_.optimizeQuery(optimizer))
        if (logger.isDebugEnabled) {
            val res = if (membersV.size == 1) membersV.head else new AbstractQueryInnerJoin(membersV)
            if (this != res)
                logger.debug("\n------------------ Members optimized individually giving new query ------------------\n" + res)
        }

        var continue = true
        while (continue) {

            for (i: Int <- 0 to (membersV.size - 2) if continue) { // from first until second to last (avant-dernier)
                for (j: Int <- (i + 1) to (membersV.size - 1) if continue) { // from i+1 until last

                    val left = membersV(i)
                    val right = membersV(j)

                    // Inner-join of 2 atomic queries
                    if (left.isInstanceOf[AbstractAtomicQuery] && right.isInstanceOf[AbstractAtomicQuery]) {

                        val leftAtom = left.asInstanceOf[AbstractAtomicQuery]
                        val rightAtom = right.asInstanceOf[AbstractAtomicQuery]

                        val merged = leftAtom.mergeForInnerJoin(right)
                        if (merged.isDefined) {
                            // ---- Merging the 2 atomic queries
                            //     i     j     =>   slice(0,i),  merged(i,j),  slice(i+1,j),  slice(j+1, size)
                            // (0, 1, 2, 3, 4) =>   0         ,  merged(1,3),  2           ,  4
                            membersV = membersV.slice(0, i) ++ List(merged.get) ++ membersV.slice(i + 1, j) ++ membersV.slice(j + 1, membersV.size)
                            continue = false
                            if (logger.isDebugEnabled) logger.debug("Self-join eliminated between queries " + i + " and " + j)
                        } else {
                            if (logger.isDebugEnabled) logger.debug("Self-join cannot be eliminated between queries " + i + " and " + j)

                            // ---- Narrowing down one of the 2 atomic queries
                            // We cannot merge the queries, but maybe at least we could narrow down one of them
                            if (MongoDBQuery.isLeftMoreSpecific(leftAtom.from, rightAtom.from)) {
                                val newRight = new AbstractAtomicQuery(rightAtom.tpBindings, leftAtom.from, rightAtom.project, rightAtom.where)
                                membersV = membersV.slice(0, j) ++ List(newRight) ++ membersV.slice(j + 1, membersV.size)
                                if (logger.isDebugEnabled) logger.debug("Narrowing down From part of query " + j + " with From part of query " + i)
                            }
                            if (MongoDBQuery.isLeftMoreSpecific(rightAtom.from, leftAtom.from)) {
                                val newLeft = new AbstractAtomicQuery(leftAtom.tpBindings, rightAtom.from, leftAtom.project, leftAtom.where)
                                membersV = membersV.slice(0, i) ++ List(newLeft) ++ membersV.slice(i + 1, membersV.size)
                                if (logger.isDebugEnabled) logger.debug("Narrowing down From part of query " + i + " with From part of query " + j)
                            }
                        }
                    }
                } // end for j
            } // end for i

            if (continue) {
                // There was no change in the last run, we cannot do anymore optimization
                if (membersV.size == 1)
                    return membersV.head
                else
                    return new AbstractQueryInnerJoin(membersV)
            } else {
                // There was a change in this run, let's rerun the optimization with the new list of members
                continue = true
                if (logger.isDebugEnabled) {
                    val res = if (membersV.size == 1) membersV.head else new AbstractQueryInnerJoin(membersV)
                    logger.debug("\n------------------ Query optimized into ------------------\n" + res)
                }
            }
        }

        throw new MorphException("We should not quit the function this way.")
    }
}

object AbstractQueryInnerJoin {

    /**
     * Constructor with a right and left query. If one of them is an inner join then its members
     * are concatenated to those of the other query.
     * This avoids embedded inner joins by construction.
     */
    def apply(left: AbstractQuery, right: AbstractQuery): AbstractQueryInnerJoin = {

        if (left.isInstanceOf[AbstractQueryInnerJoin]) {
            val leftIJ = left.asInstanceOf[AbstractQueryInnerJoin]
            if (right.isInstanceOf[AbstractQueryInnerJoin]) {
                val rightIJ = right.asInstanceOf[AbstractQueryInnerJoin]
                new AbstractQueryInnerJoin(leftIJ.members ++ rightIJ.members)
            } else
                new AbstractQueryInnerJoin(leftIJ.members :+ right)
        } else {
            if (right.isInstanceOf[AbstractQueryInnerJoin]) {
                val rightIJ = right.asInstanceOf[AbstractQueryInnerJoin]
                new AbstractQueryInnerJoin(left +: rightIJ.members)
            } else
                new AbstractQueryInnerJoin(List(left, right))
        }
    }
}
