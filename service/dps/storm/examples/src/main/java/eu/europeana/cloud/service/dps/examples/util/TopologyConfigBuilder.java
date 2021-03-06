package eu.europeana.cloud.service.dps.examples.util;

import org.apache.storm.Config;

import java.util.Properties;

import static eu.europeana.cloud.service.dps.storm.topologies.properties.TopologyPropertyKeys.*;
import static eu.europeana.cloud.service.dps.storm.topologies.properties.TopologyDefaultsConstants.*;

/**
 * @deprecated
 * Created by Tarek on 2/23/2018.
 */
@Deprecated
public class TopologyConfigBuilder {
    private TopologyConfigBuilder() {
    }

    public static Config buildConfig() {
        return buildConfig(null);
    }

    public static Config buildConfig(Properties topologyProperties) {
        Config conf = new Config();
        conf.setDebug(true);
        conf.put(Config.TOPOLOGY_DEBUG, true);
        conf.setMessageTimeoutSecs(60*60); //60 min
        //conf.setNumAckers(0);

        conf.put(CASSANDRA_HOSTS,
                getValue(topologyProperties, CASSANDRA_HOSTS, DEFAULT_CASSANDRA_HOSTS) );
        conf.put(CASSANDRA_PORT,
                getValue(topologyProperties, CASSANDRA_PORT, DEFAULT_CASSANDRA_PORT) );
        conf.put(CASSANDRA_KEYSPACE_NAME,
                getValue(topologyProperties, CASSANDRA_KEYSPACE_NAME, DEFAULT_CASSANDRA_KEYSPACE_NAME) );
        conf.put(CASSANDRA_USERNAME,
                getValue(topologyProperties, CASSANDRA_USERNAME, DEFAULT_CASSANDRA_USERNAME) );
        conf.put(CASSANDRA_SECRET_TOKEN,
                getValue(topologyProperties, CASSANDRA_SECRET_TOKEN, DEFAULT_CASSANDRA_SECRET_TOKEN) );

        return conf;
    }

    private static String getValue(Properties properties, String key, String defaultValue) {
        if(properties != null && properties.containsKey(key)) {
            return properties.getProperty(key);
        } else {
            return defaultValue;
        }
    }

}
