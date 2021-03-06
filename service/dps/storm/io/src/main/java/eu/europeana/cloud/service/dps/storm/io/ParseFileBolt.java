package eu.europeana.cloud.service.dps.storm.io;

import com.google.gson.Gson;
import com.rits.cloning.Cloner;
import eu.europeana.cloud.service.dps.PluginParameterKeys;
import eu.europeana.cloud.service.dps.storm.AbstractDpsBolt;
import eu.europeana.cloud.service.dps.storm.StormTaskTuple;
import eu.europeana.cloud.service.dps.storm.utils.StormTaskTupleHelper;
import eu.europeana.metis.mediaprocessing.RdfConverterFactory;
import eu.europeana.metis.mediaprocessing.RdfDeserializer;
import eu.europeana.metis.mediaprocessing.exception.RdfDeserializationException;
import eu.europeana.metis.mediaprocessing.model.RdfResourceEntry;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;

/**
 * Created by Tarek on 12/6/2018.
 */
public abstract class ParseFileBolt extends ReadFileBolt {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseFileBolt.class);
    private transient Gson gson;
    protected transient RdfDeserializer rdfDeserializer;

	public ParseFileBolt(String ecloudMcsAddress) {
		super(ecloudMcsAddress);
	}

	protected abstract List<RdfResourceEntry> getResourcesFromRDF(byte[] bytes) throws RdfDeserializationException;

	protected StormTaskTuple createStormTuple(StormTaskTuple stormTaskTuple, RdfResourceEntry rdfResourceEntry, int linksCount) {
		StormTaskTuple tuple = new Cloner().deepClone(stormTaskTuple);
		LOGGER.info("Sending this resource link {} to be processed ", rdfResourceEntry.getResourceUrl());
		tuple.addParameter(PluginParameterKeys.RESOURCE_LINK_KEY, gson.toJson(rdfResourceEntry));
		tuple.addParameter(PluginParameterKeys.RESOURCE_LINKS_COUNT, String.valueOf(linksCount));
		tuple.addParameter(PluginParameterKeys.RESOURCE_URL, rdfResourceEntry.getResourceUrl());
		return tuple;
	}

	protected abstract int getLinksCount(StormTaskTuple tuple, int resourcesCount) throws RdfDeserializationException;

	@Override
	public void execute(Tuple anchorTuple,  StormTaskTuple stormTaskTuple) {
		try (InputStream stream = getFileStreamByStormTuple(stormTaskTuple)) {
			byte[] fileContent = IOUtils.toByteArray(stream);
			List<RdfResourceEntry> rdfResourceEntries = getResourcesFromRDF(fileContent);
			int linksCount = getLinksCount(stormTaskTuple, rdfResourceEntries.size());
			if (linksCount == 0) {
				StormTaskTuple tuple = new Cloner().deepClone(stormTaskTuple);
				LOGGER.info("The EDM file has no resource Links ");
                outputCollector.emit(anchorTuple, tuple.toStormTuple());
			} else {
				for (RdfResourceEntry rdfResourceEntry : rdfResourceEntries) {
					if (AbstractDpsBolt.taskStatusChecker.hasKillFlag(stormTaskTuple.getTaskId()))
						break;
					StormTaskTuple tuple = createStormTuple(stormTaskTuple, rdfResourceEntry, linksCount);
					outputCollector.emit(anchorTuple, tuple.toStormTuple());
				}
			}
		} catch (Exception e) {
			LOGGER.error("Unable to read and parse file ", e);
			emitErrorNotification(anchorTuple, stormTaskTuple.getTaskId(), stormTaskTuple.getFileUrl(), e.getMessage(), "Error while reading and parsing the EDM file. The full error is: " + ExceptionUtils.getStackTrace(e),
					StormTaskTupleHelper.getRecordProcessingStartTime(stormTaskTuple));
		}
        outputCollector.ack(anchorTuple);
	}

	@Override
	public void prepare() {
		super.prepare();
		try {
			rdfDeserializer = new RdfConverterFactory().createRdfDeserializer();
			gson = new Gson();
		} catch (Exception e) {
			LOGGER.error("Unable to initialize RDF Deserializer ", e);
			throw new RuntimeException(e);
		}
	}
}
