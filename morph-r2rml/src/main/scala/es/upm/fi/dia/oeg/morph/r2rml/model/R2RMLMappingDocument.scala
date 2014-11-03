package es.upm.fi.dia.oeg.morph.r2rml.model

import scala.collection.JavaConversions._
import org.apache.log4j.Logger
import com.hp.hpl.jena.rdf.model.ModelFactory
import com.hp.hpl.jena.util.FileManager
import com.hp.hpl.jena.vocabulary.RDF
import es.upm.fi.dia.oeg.morph.base.Constants
import java.util.Collection
import es.upm.fi.dia.oeg.morph.base.sql.MorphDatabaseMetaData
import es.upm.fi.dia.oeg.morph.r2rml.MorphR2RMLElement
import es.upm.fi.dia.oeg.morph.r2rml.MorphR2RMLElementVisitor
import java.sql.Connection
import es.upm.fi.dia.oeg.morph.base.model.MorphBasePropertyMapping
import es.upm.fi.dia.oeg.morph.base.model.MorphBaseMappingDocument
import es.upm.fi.dia.oeg.morph.base.model.MorphBaseClassMapping
import es.upm.fi.dia.oeg.morph.base.MorphProperties

class R2RMLMappingDocument(classMappings: Iterable[R2RMLTriplesMap])
        extends MorphBaseMappingDocument(classMappings) with MorphR2RMLElement {

    override val logger = Logger.getLogger(this.getClass());

    def buildMetaData(conn: Connection, databaseName: String, databaseType: String) = {
        logger.info("building database MetaData ");
        if (conn != null && this.dbMetaData == None) {
            val newMetaData = MorphDatabaseMetaData(conn, databaseName, databaseType);
            this.dbMetaData = Some(newMetaData);
            this.classMappings.foreach(cm => cm.buildMetaData(this.dbMetaData));
        }
    }

    def accept(visitor: MorphR2RMLElementVisitor): Object = { visitor.visit(this); }

    /**
     * Find the list of predicates that are used in all PredicateObjectMaps of all TriplesMaps of the document
     * @return a list of predicate names
     */
    override def getMappedProperties(): Iterable[String] = {
        val cms = this.classMappings.toList;
        val resultAux = cms.map(cm => {
            val tm = cm.asInstanceOf[R2RMLTriplesMap];
            val poms = tm.getPropertyMappings().toList;
            val mappedPredicateNames = poms.map(pom => { pom.getMappedPredicateNames().toList });
            val flatMappedPredicateNames = mappedPredicateNames.flatten;
            flatMappedPredicateNames;
        })

        val result = resultAux.flatten;
        result;
    }

    def getParentTriplesMap(refObjectMap: R2RMLRefObjectMap): R2RMLTriplesMap = {
        // Build the list of JENA resources that correspond to all triples map that are 
        // referenced as a parent triples map by the given referencing object map.
        val parentTripleMapResources = this.classMappings.map(cm => {
            val tm = cm.asInstanceOf[R2RMLTriplesMap];
            val poms = tm.predicateObjectMaps;
            poms.map(pom => {
                val roms = pom.refObjectMaps; // pom.refObjectMaps may be an empty list but not null
                roms.flatMap(rom => {
                    if (rom == refObjectMap) {
                        Some(rom.parentTriplesMapResource);
                    } else { None }
                });
            }).flatten
        }).flatten;

        val parentTripleMaps = this.classMappings.filter(cm => {
            val tm = cm.asInstanceOf[R2RMLTriplesMap];
            parentTripleMapResources.exists(parentTripleMapResource => {
                tm.resource == parentTripleMapResource;
            })
        })

        val parentTripleMap = parentTripleMaps.iterator.next;
        parentTripleMap.asInstanceOf[R2RMLTriplesMap]
    }

    override def getPossibleRange(predicateURI: String): Iterable[MorphBaseClassMapping] = {
        val pms = this.getPropertyMappingsByPropertyURI(predicateURI).toList;
        val resultAux = if (pms != null) {
            pms.map(pm => {
                val possibleRange = this.getPossibleRange(pm).toList;
                possibleRange;
            })
        } else {
            Nil
        }

        val resultInList = resultAux.flatten;
        val resultInSet = resultInList.toSet;
        resultInSet
    }

    override def getPossibleRange(predicateURI: String, cm: MorphBaseClassMapping): Iterable[MorphBaseClassMapping] = {
        val pms = cm.getPropertyMappings(predicateURI);
        val result = if (pms != null) {
            pms.toList.map(pm => {
                val possibleRange = this.getPossibleRange(pm);
                possibleRange;
            })
        } else {
            Nil
        }

        val resultInList = result.flatten;
        val resultInSet = resultInList.toSet;
        resultInSet;
    }

    override def getPossibleRange(pm: MorphBasePropertyMapping): Iterable[MorphBaseClassMapping] = {

        val pom = pm.asInstanceOf[R2RMLPredicateObjectMap];
        val om = pom.getObjectMap(0);
        val rom = pom.getRefObjectMap(0);
        val cms = this.classMappings

        val result: Iterable[MorphBaseClassMapping] = if (om != null && rom == null) {
            val inferredTermType = om.inferTermType;
            if (Constants.R2RML_IRI_URI.equals(inferredTermType)) {
                if (cms != null) {
                    Nil
                } else {
                    cms.toList.flatMap(cm => {
                        val tm = cm.asInstanceOf[R2RMLTriplesMap];
                        if (Constants.MorphTermMapType.TemplateTermMap == om.termMapType) {
                            val objectTemplateString = om.getTemplateString();
                            if (tm.isPossibleInstance(objectTemplateString)) {
                                Some(cm);
                            } else {
                                None
                            }
                        } else {
                            None
                        }
                    })
                }
            } else {
                Nil
            }
        } else if (rom != null && om == null) {
            val parentTriplesMap = this.getParentTriplesMap(rom);
            val parentSubjectMap = parentTriplesMap.subjectMap;
            if (parentSubjectMap.termMapType == Constants.MorphTermMapType.TemplateTermMap) {
                val templateString = parentSubjectMap.getTemplateString();
                if (cms == null) {
                    Nil
                } else {
                    cms.flatMap(cm => {
                        if (cm.isPossibleInstance(templateString)) {
                            val tm2 = cm.asInstanceOf[R2RMLTriplesMap];
                            val classURIs = tm2.subjectMap.classURIs;
                            if (classURIs != null && !classURIs.isEmpty()) {
                                Some(cm);
                            } else {
                                None
                            }
                        } else {
                            None
                        }
                    })
                }
            } else {
                List(parentTriplesMap);
            }
        } else {
            Nil
        }

        val resultInSet = result.toSet;
        resultInSet
    }

    def getClassMappingsByInstanceTemplate(templateValue: String): Iterable[MorphBaseClassMapping] = {
        this.classMappings.filter(cm => {
            val tm = cm.asInstanceOf[R2RMLTriplesMap]
            tm.subjectMap.templateString.startsWith(templateValue)
        })
    }

    def getClassMappingsByInstanceURI(instanceURI: String): Iterable[MorphBaseClassMapping] = {
        this.classMappings.filter(cm => {
            val possibleInstance = cm.isPossibleInstance(instanceURI)
            possibleInstance
        })
    }

}

object R2RMLMappingDocument {
    val logger = Logger.getLogger(this.getClass().getName());

    def apply(mdPath: String): R2RMLMappingDocument = {
        R2RMLMappingDocument(mdPath, null, null);
    }

    def apply(mdPath: String, props: MorphProperties, connection: Connection): R2RMLMappingDocument = {
        logger.info("Creating R2RMLMappingDocument ");

        val model = ModelFactory.createDefaultModel();
        // use the FileManager to find the input file
        val in = FileManager.get().open(mdPath);
        if (in == null) {
            throw new IllegalArgumentException("Mapping File: " + mdPath + " not found");
        }

        logger.info("Parsing mapping document " + mdPath);
        // read the Turtle file
        model.read(in, null, "TURTLE");

        // Get the list of JENA resources representing triples maps
        val tmList = model.listResourcesWithProperty(RDF.`type`, Constants.R2RML_TRIPLESMAP_CLASS);

        // Build an equivalent list of R2RMLTriplesMap instances, id is the name of the triples map local name (like #MyTiplesMap) 
        val classMappings = if (tmList != null) {
            tmList.map(tmRes => {
                val triplesMapKey = tmRes.getLocalName();
                // From that point on, each triples map is browsed in depth to create the whole model (logical source, term maps)
                val tm = R2RMLTriplesMap(tmRes);
                tm.id = triplesMapKey;
                tm;
            })
        } else {
            Set.empty
        }

        // From the list of R2RMLTriplesMap, create an R2RML mapping document
        val md = new R2RMLMappingDocument(classMappings.toSet);
        md.mappingDocumentPath = mdPath;

        if (connection != null) {
            //BUILDING METADATA
            try {
                md.buildMetaData(connection, props.databaseName, props.databaseType);
            } catch {
                case e: Exception => { logger.warn("Error while building metadata.") }
            }
        }

        md.mappingDocumentPrefixMap = model.getNsPrefixMap().toMap;
        md
    }
}