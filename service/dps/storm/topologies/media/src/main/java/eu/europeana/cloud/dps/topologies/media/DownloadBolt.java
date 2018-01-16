package eu.europeana.cloud.dps.topologies.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import eu.europeana.cloud.common.model.File;
import eu.europeana.cloud.common.model.Representation;
import eu.europeana.cloud.dps.topologies.media.support.FileInfo;
import eu.europeana.cloud.dps.topologies.media.support.MediaException;
import eu.europeana.cloud.dps.topologies.media.support.MediaTupleData;
import eu.europeana.cloud.dps.topologies.media.support.MediaTupleData.UrlType;
import eu.europeana.cloud.dps.topologies.media.support.StatsTupleData;
import eu.europeana.cloud.dps.topologies.media.support.Util;
import eu.europeana.cloud.mcs.driver.FileServiceClient;

public class DownloadBolt extends BaseRichBolt {
	
	private static final Logger logger = LoggerFactory.getLogger(DownloadBolt.class);
	
	private OutputCollector outputCollector;
	
	private FileServiceClient fileClient;
	
	private DocumentBuilder documentBuilder;
	
	private CloseableHttpClient httpClient;
	
	@Override
	public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
		outputCollector = collector;
		
		fileClient = Util.getFileServiceClient(stormConf);
		
		try {
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			dbFactory.setNamespaceAware(true);
			documentBuilder = dbFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new RuntimeException("xml problem", e);
		}
		
		httpClient = HttpClients.createDefault();
	}
	
	@Override
	public void cleanup() {
		try {
			httpClient.close();
		} catch (IOException e) {
			logger.error("HttpClient could not close", e);
		}
	}
	
	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declare(new Fields(MediaTupleData.FIELD_NAME, StatsTupleData.FIELD_NAME));
		declarer.declareStream(StatsTupleData.STREAM_ID, new Fields(StatsTupleData.FIELD_NAME));
	}
	
	@Override
	public void execute(Tuple input) {
		MediaTupleData data = (MediaTupleData) input.getValueByField(MediaTupleData.FIELD_NAME);
		
		Map<String, Set<UrlType>> urls;
		try {
			Representation rep = data.getEdmRepresentation();
			List<File> files = rep.getFiles();
			if (files.size() != 1)
				throw new MediaException("EDM representation has " + files.size() + " files!");
			Document edm = getEdmDocument(rep, files.get(0));
			urls = retrieveUrls(edm);
			
			data.setEdm(edm);
		} catch (MediaException e) {
			logger.error("Could not retrieve media urls from representation " + data.getEdmRepresentation(), e);
			outputCollector.fail(input);
			return;
		}
		
		StatsTupleData stats = new StatsTupleData(data.getTaskId(), urls.size());
		stats.setDownloadStartTime(System.currentTimeMillis());
		long byteCount = 0;
		
		for (Entry<String, Set<UrlType>> entry : urls.entrySet()) {
			try {
				FileInfo file = downloadFile(entry.getKey());
				file.setTypes(entry.getValue());
				data.addFileInfo(file);
				byteCount += file.getLength();
			} catch (MediaException e) {
				logger.info("Download failed ({}): {}", e.getMessage(), entry.getKey());
				logger.trace("download failure details:", e);
				stats.addError(e.getMessage());
			}
		}
		if (!data.getFileInfos().isEmpty()) {
			stats.setDownloadedBytes(byteCount);
			outputCollector.emit(input, new Values(data, stats));
		} else {
			outputCollector.emit(StatsTupleData.STREAM_ID, input, new Values(stats));
		}
		outputCollector.ack(input);
	}
	
	private Map<String, Set<UrlType>> retrieveUrls(Document edm) throws MediaException {
		Map<String, Set<UrlType>> urls = new HashMap<>();
		long start = System.currentTimeMillis();
		
		for (UrlType urlType : Arrays.asList(UrlType.OBJECT, UrlType.HAS_VIEW, UrlType.IS_SHOWN_BY)) {
			NodeList list = edm.getElementsByTagName(urlType.tagName);
			for (int i = 0; i < list.getLength(); i++) {
				String url = ((Element) list.item(i)).getAttribute("rdf:resource");
				urls.computeIfAbsent(url, e -> new HashSet<>()).add(urlType);
			}
		}
		
		logger.debug("edm document retrieving took {} ms", System.currentTimeMillis() - start);
		
		if (urls.isEmpty())
			throw new MediaException("edm file representation doesn't contain content urls");
		return urls;
	}
	
	@SuppressWarnings("resource") // response shouldn't be closed, it may prevent connection reuse
	private FileInfo downloadFile(String fileUrl) throws MediaException {
		long start = System.currentTimeMillis();
		try {
			HttpResponse response = httpClient.execute(new HttpGet(fileUrl));
			
			try {
				int status = response.getStatusLine().getStatusCode();
				if (status >= 200 && status < 300) {
					String mimeType = ContentType.get(response.getEntity()).getMimeType();
					byte[] content = EntityUtils.toByteArray(response.getEntity());
					return new FileInfo(fileUrl, mimeType, content);
				} else {
					throw new MediaException("STATUS CODE " + status);
				}
			} finally {
				EntityUtils.consume(response.getEntity());
				logger.debug("download took {} ms: {}", System.currentTimeMillis() - start, fileUrl);
			}
		} catch (IOException e) {
			throw new MediaException(e.getMessage());
		}
	}
	
	private Document getEdmDocument(Representation rep, File file) throws MediaException {
		try {
			try (InputStream is = fileClient.getFile(rep.getCloudId(), rep.getRepresentationName(), rep.getVersion(),
					file.getFileName())) {
				return documentBuilder.parse(is);
			}
		} catch (Exception e) {
			throw new MediaException("Could not read edm file", e);
		}
		
	}
}
