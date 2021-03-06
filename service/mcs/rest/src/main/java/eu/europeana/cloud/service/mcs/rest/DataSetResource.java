package eu.europeana.cloud.service.mcs.rest;

import eu.europeana.cloud.common.model.CloudIdAndTimestampResponse;
import eu.europeana.cloud.common.model.DataSet;
import eu.europeana.cloud.common.model.Representation;
import eu.europeana.cloud.common.model.RepresentationNames;
import eu.europeana.cloud.common.response.CloudVersionRevisionResponse;
import eu.europeana.cloud.common.response.ResultSlice;
import eu.europeana.cloud.common.utils.Tags;
import eu.europeana.cloud.service.aas.authentication.SpringUserUtils;
import eu.europeana.cloud.service.mcs.DataSetService;
import eu.europeana.cloud.service.mcs.exception.AccessDeniedOrObjectDoesNotExistException;
import eu.europeana.cloud.service.mcs.exception.DataSetNotExistsException;
import eu.europeana.cloud.service.mcs.exception.ProviderNotExistsException;
import eu.europeana.cloud.service.mcs.utils.EnrichUriUtil;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.acls.domain.ObjectIdentityImpl;
import org.springframework.security.acls.model.MutableAclService;
import org.springframework.security.acls.model.ObjectIdentity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

import static eu.europeana.cloud.service.mcs.RestInterfaceConstants.*;

/**
 * Resource to manage data sets.
 */
@RestController
public class DataSetResource {
    private static final String DATASET_CLASS_NAME = DataSet.class.getName();

    private final DataSetService dataSetService;

    private final MutableAclService mutableAclService;

    @Value("${numberOfElementsOnPage}")
    private int numberOfElementsOnPage;

    public DataSetResource(
            DataSetService dataSetService,
            MutableAclService mutableAclService) {
        this.dataSetService = dataSetService;
        this.mutableAclService = mutableAclService;
    }

    /**
     * Deletes data set.
     * <strong>Delete permissions required.</strong>
     *
     * @param providerId identifier of the dataset's provider(required).
     * @param dataSetId  identifier of the deleted data set(required).
     * @throws DataSetNotExistsException data set not exists.
     */
    @DeleteMapping(DATA_SET_RESOURCE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#dataSetId.concat('/').concat(#providerId), 'eu.europeana.cloud.common.model.DataSet', delete)")
    public void deleteDataSet(
            @PathVariable String dataSetId,
            @PathVariable String providerId) throws DataSetNotExistsException {

        dataSetService.deleteDataSet(providerId, dataSetId);

        // let's delete the permissions as well
        String ownersName = SpringUserUtils.getUsername();
        if (ownersName != null) {
            ObjectIdentity dataSetIdentity = new ObjectIdentityImpl(DATASET_CLASS_NAME,
                    dataSetId + "/" + providerId);
            mutableAclService.deleteAcl(dataSetIdentity, false);
        }
    }

    /**
     * Lists representation versions from data set. Result is returned in
     * slices.
     *
     * @param providerId identifier of the dataset's provider (required).
     * @param dataSetId  identifier of a data set (required).
     * @param startFrom  reference to next slice of result. If not provided,
     *                   first slice of result will be returned.
     * @return slice of representation version list.
     * @throws DataSetNotExistsException no such data set exists.
     * @summary get representation versions from a data set
     */
    @GetMapping(value = DATA_SET_RESOURCE, produces = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @ResponseBody
    public ResultSlice<Representation> getDataSetContents(
            HttpServletRequest httpServletRequest,
            @PathVariable String dataSetId,
            @PathVariable String providerId,
            @RequestParam(required = false) String startFrom) throws DataSetNotExistsException {

        ResultSlice<Representation> representations =
                dataSetService.listDataSet(providerId, dataSetId, startFrom, numberOfElementsOnPage);

        for (Representation rep : representations.getResults()) {
            EnrichUriUtil.enrich(httpServletRequest, rep);
        }
        return representations;
    }

    /**
     * Updates description of a data set.
     * <p>
     * <strong>Write permissions required.</strong>
     *
     * @param providerId  identifier of the dataset's provider (required).
     * @param dataSetId   identifier of a data set (required).
     * @param description description of data set
     * @throws DataSetNotExistsException                 no such data set exists.
     * @throws AccessDeniedOrObjectDoesNotExistException there is an attempt to access a resource without the proper permissions.
     *                                                   or the resource does not exist at all
     * @statuscode 204 object has been updated.
     */
    @PutMapping(DATA_SET_RESOURCE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#dataSetId.concat('/').concat(#providerId), 'eu.europeana.cloud.common.model.DataSet', write)")
    public void updateDataSet(
            @PathVariable String dataSetId,
            @PathVariable String providerId,
            @RequestParam String description) throws DataSetNotExistsException {

        dataSetService.updateDataSet(providerId, dataSetId, description);
    }

    @GetMapping(value = DATA_SET_REPRESENTATIONS_NAMES,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    @ResponseBody
    public RepresentationNames getRepresentationsNames(
            @PathVariable String dataSetId,
            @PathVariable String providerId) throws ProviderNotExistsException, DataSetNotExistsException {

        RepresentationNames representationNames = new RepresentationNames();
        representationNames.setNames(dataSetService.getAllDataSetRepresentationsNames(providerId, dataSetId));
        return representationNames;
    }

    @GetMapping(value = DATA_SET_BY_REPRESENTATION,
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @ResponseBody
    public ResultSlice<CloudVersionRevisionResponse> getDataSetCloudIdsByRepresentation(
            @PathVariable String dataSetId,
            @PathVariable String providerId,
            @PathVariable String representationName,
            @RequestParam String creationDateFrom,
            @RequestParam String tag,
            @RequestParam(required = false) String startFrom) throws ProviderNotExistsException, DataSetNotExistsException {

        Tags tags = Tags.valueOf(tag.toUpperCase());
        DateTime utc = new DateTime(creationDateFrom, DateTimeZone.UTC);

        if (Tags.PUBLISHED.equals(tags)) {
            return dataSetService.getDataSetCloudIdsByRepresentationPublished(dataSetId, providerId, representationName, utc.toDate(), startFrom, numberOfElementsOnPage);
        }
        throw new IllegalArgumentException("Only PUBLISHED tag is supported for this request.");
    }

    /**
     * get a list of the latest cloud identifiers,revision timestamps that belong to data set of a specified provider for a specific representation and revision.
     * This list will contain one row per revision per cloudId;
     *
     * @param dataSetId          data set identifier
     * @param providerId         provider identifier
     * @param revisionName       revision name
     * @param revisionProvider   revision provider
     * @param representationName representation name
     * @param startFrom          cloudId to start from
     * @param deleted          revision marked-deleted
     * @return slice of the latest cloud identifier,revision timestamp that belong to data set of a specified provider for a specific representation and revision
     * This list will contain one row per revision per cloudId ;
     * @throws ProviderNotExistsException
     * @throws DataSetNotExistsException
     */
    @GetMapping(value = DATA_SET_BY_REPRESENTATION_REVISION,
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @ResponseBody
    public ResultSlice<CloudIdAndTimestampResponse> getDataSetCloudIdsByRepresentationAndRevision(
            @PathVariable String dataSetId,
            @PathVariable String providerId,
            @PathVariable String revisionName,
            @PathVariable String revisionProvider,
            @PathVariable String representationName,
            @RequestParam(required = false) String startFrom,
            @RequestParam(required = false) Boolean deleted) throws ProviderNotExistsException, DataSetNotExistsException {

        return dataSetService.getLatestDataSetCloudIdByRepresentationAndRevision(dataSetId, providerId, revisionName,
                revisionProvider, representationName, startFrom, deleted, numberOfElementsOnPage);
    }

    /**
     * Gives the versionId of specified representation that has the newest revision (by revision timestamp) with given name.
     *
     * @param dataSetId          dataset identifier
     * @param providerId         dataset owner
     * @param cloudId            representation cloud identifier
     * @param representationName representation name
     * @param revisionName       revision name
     * @param revisionProviderId revision owner
     * @return version identifier of representation
     * @throws DataSetNotExistsException
     */
    @GetMapping(value = DATA_SET_LATELY_REVISIONED_VERSION,
            produces = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE, })
    public ResponseEntity<String> getLatelyTaggedRecords(
            @PathVariable String dataSetId,
            @PathVariable String providerId,
            @RequestParam String cloudId,
            @RequestParam String representationName,
            @RequestParam String revisionName,
            @RequestParam String revisionProviderId) throws DataSetNotExistsException {

        String versionId = dataSetService.getLatestVersionForGivenRevision(dataSetId, providerId, cloudId,
                representationName, revisionName, revisionProviderId);
        if (versionId != null) {
            return ResponseEntity.ok(versionId);
        } else {
            return ResponseEntity.noContent().build();
        }
    }
}
