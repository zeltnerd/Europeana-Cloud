package eu.europeana.cloud.service.dls.listeners;

import eu.europeana.cloud.service.dls.solr.SolrDAO;
import java.io.IOException;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Listener class which reacts on messages about deletion of record's
 * representation version.
 */
@Component
public class RepresentationVersionRemovedListener implements MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepresentationVersionRemovedListener.class);

    @Autowired
    SolrDAO solrDao;

    /**
     *
     * @param message
     */
    @Override
    public void onMessage(Message message) {

        byte[] messageBytes = message.getBody();
        if (messageBytes == null) {
            LOGGER.error("Message has null body.");
            return;
        }

        String messageText = new String(message.getBody());
        if (messageText.isEmpty()) {
            LOGGER.error("Message has empty body.");
            return;
        }

        String versionId = messageText;

        if (StringUtils.isBlank(versionId)) {
            LOGGER.error("Required parameter version id is empty.");
            return;
        }

        try {
            solrDao.removeRepresentationVersion(versionId);
        } catch (SolrServerException | IOException ex) {
            LOGGER.error("Cannot remove representation from solr.", ex);
        }

    }
}