package eu.europeana.cloud.service.mcs;

import java.util.List;

import eu.europeana.cloud.common.model.DataProvider;
import eu.europeana.cloud.common.model.DataProviderProperties;
import eu.europeana.cloud.common.model.DataSet;
import eu.europeana.cloud.service.mcs.exception.DataSetAlreadyExistsException;
import eu.europeana.cloud.service.mcs.exception.DataSetNotExistsException;
import eu.europeana.cloud.service.mcs.exception.ProviderHasDataSetsException;
import eu.europeana.cloud.service.mcs.exception.ProviderHasRecordsException;
import eu.europeana.cloud.service.mcs.exception.ProviderNotExistsException;

/**
 * DataProviderService
 *
 */
public interface DataProviderService {

    List<DataProvider> getProviders();


    DataProvider getProvider(String providerId)
            throws ProviderNotExistsException;


    DataProvider createProvider(String providerId, DataProviderProperties properties);


    void deleteProvider(String providerId)
            throws ProviderNotExistsException, ProviderHasDataSetsException, ProviderHasRecordsException;
}
