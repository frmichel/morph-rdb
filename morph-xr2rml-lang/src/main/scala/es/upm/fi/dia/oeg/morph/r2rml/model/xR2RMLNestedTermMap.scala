package es.upm.fi.dia.oeg.morph.r2rml.model

import org.apache.log4j.Logger

import es.upm.fi.dia.oeg.morph.base.Constants

/**
 * @todo This class is only a partial implementation of xR2RML nested term maps: it only support simple nested term maps,
 * i.e. without any xrr:reference, rr:template nor xrr:nestedTermMap property.
 * It can be used only to qualify terms of an RDF collection or container generated by the parent term map.
 */
class xR2RMLNestedTermMap(
        /** Type of the root parent term map, used to infer the term type if it is not provided explicitly */
        parentTermMapType: Constants.MorphTermMapType.Value,
        termType: Option[String],
        val datatype: Option[String],
        val languageTag: Option[String],
        nestedTermMap: Option[xR2RMLNestedTermMap]) {

    val logger = Logger.getLogger(this.getClass().getName());

    override def toString(): String = {
        "NestedTermMap[termType:" + termType + ", datatype:" + datatype + ", language:" + languageTag + "]";
    }

    /**
     * Return true if the nested term map has a xrr:reference property
     */
    def isReferenceValuedNestedTermMap = { false }

    /**
     * Return true if the nested term map has a rr:template property
     */
    def isTemplateValuedNestedTermMap = { false }

    /**
     * Return true if the nested term map has no xrr:reference nor rr:template property
     */
    def isSimpleNestedTermMap = { true }

    /**
     * Return the term type mentioned by property rr:termType or the default term type otherwise
     */
    def inferTermType: String = {
        this.termType.getOrElse(this.getDefaultTermType)
    }

    def getDefaultTermType: String = {
        parentTermMapType match {
            case Constants.MorphTermMapType.ColumnTermMap => Constants.R2RML_LITERAL_URI
            case Constants.MorphTermMapType.ReferenceTermMap => Constants.R2RML_LITERAL_URI
            case Constants.MorphTermMapType.TemplateTermMap => Constants.R2RML_IRI_URI
            case _ => Constants.R2RML_LITERAL_URI
        }
    }

    /**
     * Return true if the term type's term map is one of RDF list, bag, seq, alt
     */
    def isRdfCollectionTermType: Boolean = {
        if (this.termType.isDefined) {
            val tt = this.termType.get
            (tt == Constants.xR2RML_RDFLIST_URI ||
                tt == Constants.xR2RML_RDFBAG_URI ||
                tt == Constants.xR2RML_RDFSEQ_URI ||
                tt == Constants.xR2RML_RDFALT_URI)
        } else { false }
    }
}

