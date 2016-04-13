package es.upm.fi.dia.oeg.morph.base.engine

import es.upm.fi.dia.oeg.morph.base.GenericConnection
import es.upm.fi.dia.oeg.morph.base.MorphBaseResultSet
import es.upm.fi.dia.oeg.morph.base.query.GenericQuery

/**
 * A data source reader is used to execute queries against the target database
 * 
 * @author Freddy Priyatna
 * @author Franck Michel, I3S laboratory
 */
abstract class MorphBaseDataSourceReader(val factory: IMorphFactory) {

    /**
     * Execute a target database query against the connection.
     * @return a concrete instance of MorphBaseResultSet. Must NOT return null, may return an empty result.
     */
    def execute(query: GenericQuery): MorphBaseResultSet

    /**
     * Execute a target database against the connection and apply an rml:iterator on the results.
     * @return a concrete instance of MorphBaseResultSet. Must NOT return null, may return an empty result.
     */
    def executeQueryAndIterator(query: GenericQuery, logSrcIterator: Option[String]): MorphBaseResultSet

    def setTimeout(timeout: Int)

    def closeConnection()
}