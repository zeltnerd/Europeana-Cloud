package eu.europeana.cloud.service.dps.rest;

import com.qmino.miredot.annotations.ReturnType;
import eu.europeana.cloud.common.model.DataSet;
import eu.europeana.cloud.common.model.dps.TaskInfo;
import eu.europeana.cloud.common.model.dps.TaskState;
import eu.europeana.cloud.mcs.driver.DataSetServiceClient;
import eu.europeana.cloud.service.commons.urls.UrlParser;
import eu.europeana.cloud.service.commons.urls.UrlPart;
import eu.europeana.cloud.service.dps.DpsTask;
import eu.europeana.cloud.service.dps.PluginParameterKeys;
import eu.europeana.cloud.service.dps.TaskExecutionKillService;
import eu.europeana.cloud.service.dps.TaskExecutionReportService;
import eu.europeana.cloud.service.dps.exception.AccessDeniedOrObjectDoesNotExistException;
import eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException;
import eu.europeana.cloud.service.dps.exception.DpsTaskValidationException;
import eu.europeana.cloud.service.dps.exception.TaskInfoDoesNotExistException;
import eu.europeana.cloud.service.dps.metis.indexing.DataSetCleanerParameters;
import eu.europeana.cloud.service.dps.service.utils.TopologyManager;
import eu.europeana.cloud.service.dps.service.utils.validation.DpsTaskValidator;
import eu.europeana.cloud.service.dps.services.DatasetCleanerService;
import eu.europeana.cloud.service.dps.services.SubmitTaskService;
import eu.europeana.cloud.service.dps.storm.utils.CassandraTablesAndColumnsNames;
import eu.europeana.cloud.service.dps.storm.utils.CassandraTaskInfoDAO;
import eu.europeana.cloud.service.dps.storm.utils.TaskStatusChecker;
import eu.europeana.cloud.service.dps.storm.utils.TasksByStateDAO;
import eu.europeana.cloud.service.dps.structs.SubmitTaskParameters;
import eu.europeana.cloud.service.dps.utils.DpsTaskValidatorFactory;
import eu.europeana.cloud.service.dps.utils.PermissionManager;
import eu.europeana.cloud.service.mcs.exception.DataSetNotExistsException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static eu.europeana.cloud.service.dps.InputDataType.*;

/**
 * Resource to fetch / submit Tasks to the DPS service
 */
@RestController
@RequestMapping("/{topologyName}/tasks")
public class TopologyTasksResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopologyTasksResource.class);

    private static final String TOPOLOGY_PREFIX = "Topology";

    public static final String TASK_PREFIX = "DPS_Task";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TaskExecutionReportService reportService;

    @Autowired
    private TaskExecutionKillService killService;

    @Autowired
    private TopologyManager topologyManager;

    @Autowired
    private PermissionManager permissionManager;

    @Autowired
    private DataSetServiceClient dataSetServiceClient;

    @Autowired
    private CassandraTaskInfoDAO taskInfoDAO;

    @Autowired
    private TasksByStateDAO tasksByStateDAO;

    @Autowired
    private DatasetCleanerService datasetCleanerService;

    @Autowired
    private SubmitTaskService submitTaskService;

    @Autowired
    private String applicationIdentifier;

    /**
     * Retrieves the current progress for the requested task.
     * <p/>
     * <br/><br/>
     * <div style='border-left: solid 5px #999999; border-radius: 10px; padding: 6px;'>
     * <strong>Required permissions:</strong>
     * <ul>
     * <li>Read permissions for selected task</li>
     * </ul>
     * </div>
     *
     * @param topologyName <strong>REQUIRED</strong> Name of the topology where the task is submitted.
     * @param taskId       <strong>REQUIRED</strong> Unique id that identifies the task.
     * @return Progress for the requested task
     * (number of records of the specified task that have been fully processed).
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrObjectDoesNotExistException   if task does not exist or access to the task is denied for the user
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException if topology does not exist or access to the topology is denied for the user
     * @summary Get Task Progress
     */

    @GetMapping(value = "{taskId}/progress", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    @PreAuthorize("hasPermission(#taskId,'" + TASK_PREFIX + "', read)")
    public TaskInfo getTaskProgress(
            @PathVariable final String topologyName,
            @PathVariable final String taskId) throws
                            AccessDeniedOrObjectDoesNotExistException, AccessDeniedOrTopologyDoesNotExistException {
        assertContainTopology(topologyName);
        reportService.checkIfTaskExists(taskId, topologyName);
        TaskInfo progress = reportService.getTaskProgress(taskId);
        return progress;
    }

    /**
     * Submits a Task for execution.
     * Each Task execution is associated with a specific plugin.
     * <p/>
     * <strong>Write permissions required</strong>.
     *
     * @param task         <strong>REQUIRED</strong> Task to be executed. Should contain links to input data,
     *                     either in form of cloud-records or cloud-datasets.
     * @param topologyName <strong>REQUIRED</strong> Name of the topology where the task is submitted.
     * @return URI with information about the submitted task execution.
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException if topology does not exist or access to the topology is denied for the user
     * @summary Submit Task
     * @summary Submit Task
     */
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasPermission(#topologyName,'" + TOPOLOGY_PREFIX + "', write)")
    public ResponseEntity<Void> submitTask(
            final HttpServletRequest request,
            @RequestBody final DpsTask task,
            @PathVariable final String topologyName,
            @RequestHeader("Authorization") final String authorizationHeader
    ) throws AccessDeniedOrTopologyDoesNotExistException, DpsTaskValidationException, IOException {
        return doSubmitTask(request, task, topologyName, authorizationHeader, false);
    }

    /**
     * Restarts a Task for execution.
     * Each Task execution is associated with a specific plugin.
     * <p/>
     * <strong>Write permissions required</strong>.
     *
     * @param taskId       <strong>REQUIRED</strong> Task identifier to be processed.
     * @param topologyName <strong>REQUIRED</strong> Name of the topology where the task is submitted.
     * @return URI with information about the submitted task execution.
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException if topology does not exist or access to the topology is denied for the user
     * @summary Submit Task
     * @summary Submit Task
     */
    @PostMapping(path = "{taskId}/restart", consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasPermission(#topologyName,'" + TOPOLOGY_PREFIX + "', write)")
    public ResponseEntity<Void> restartTask(
            final HttpServletRequest request,
            @PathVariable final long taskId,
            @PathVariable final String topologyName,
            @RequestHeader("Authorization") final String authorizationHeader
    ) throws TaskInfoDoesNotExistException, AccessDeniedOrTopologyDoesNotExistException, DpsTaskValidationException, IOException {
        TaskInfo taskInfo = taskInfoDAO.searchById(taskId);
        DpsTask task = new ObjectMapper().readValue(taskInfo.getTaskDefinition(), DpsTask.class);
        return doSubmitTask(request, task, topologyName, authorizationHeader, true);
    }

    @PostMapping(path = "{taskId}/cleaner", consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasPermission(#topologyName,'" + TOPOLOGY_PREFIX + "', write)")
    public ResponseEntity<Void> cleanIndexingDataSet(
            @PathVariable final String topologyName,
            @PathVariable final String taskId,
            @RequestBody final DataSetCleanerParameters cleanerParameters
    ) throws AccessDeniedOrTopologyDoesNotExistException, AccessDeniedOrObjectDoesNotExistException {
        LOGGER.info("Cleaning parameters for: {}", cleanerParameters);

        assertContainTopology(topologyName);
        reportService.checkIfTaskExists(taskId, topologyName);
        datasetCleanerService.clean(taskId, cleanerParameters);
        return ResponseEntity.ok().build();
    }

    /**
     * Grants read / write permissions for a task to the specified user.
     * <p/>
     * <br/><br/>
     * <div style='border-left: solid 5px #999999; border-radius: 10px; padding: 6px;'>
     * <strong>Required permissions:</strong>
     * <ul>
     * <li>Admin permissions</li>
     * </ul>
     * </div>
     *
     * @param taskId       <strong>REQUIRED</strong> Unique id that identifies the task.
     * @param topologyName <strong>REQUIRED</strong> Name of the topology where the task is submitted.
     * @param username     <strong>REQUIRED</strong> Permissions are granted to the account with this unique username
     * @return Status code indicating whether the operation was successful or not.
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException if topology does not exist or access to the topology is denied for the user
     * @summary Grant task permissions to user
     */

    @PostMapping(path = "{taskId}/permit")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @ReturnType("java.lang.Void")
    public ResponseEntity<Void> grantPermissions(
            @PathVariable String topologyName,
            @PathVariable String taskId,
            @RequestParam String username) throws AccessDeniedOrTopologyDoesNotExistException {

        assertContainTopology(topologyName);

        if (taskId != null) {
            permissionManager.grantPermissionsForTask(taskId, username);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
    }

    /**
     * Submit kill flag to the specific task.
     * <div style='border-left: solid 5px #999999; border-radius: 10px; padding: 6px;'>
     * <strong>Required permissions:</strong>
     * <ul>
     * <li>Authenticated user</li>
     * <li>Write permission for selected task</li>
     * </ul>
     * </div>
     *
     * @param taskId       <strong>REQUIRED</strong> Unique id that identifies the task.
     * @param topologyName <strong>REQUIRED</strong> Name of the topology where the task is submitted.
     * @param info         <strong>OPTIONAL</strong> The cause of the cancellation. If it was not specified a default cause 'Dropped by the user' will be provided
     * @return Status code indicating whether the operation was successful or not.
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrTopologyDoesNotExistException if topology does not exist or access to the topology is denied for the user
     * @throws eu.europeana.cloud.service.dps.exception.AccessDeniedOrObjectDoesNotExistException   if taskId does not belong to the specified topology
     * @summary Kill task
     */

    @PostMapping(path = "{taskId}/kill")
    @PreAuthorize("hasPermission(#taskId,'" + TASK_PREFIX + "', write)")
    public ResponseEntity<String> killTask(
            @PathVariable String topologyName,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "Dropped by the user") String info)
                    throws AccessDeniedOrTopologyDoesNotExistException, AccessDeniedOrObjectDoesNotExistException {
        assertContainTopology(topologyName);
        reportService.checkIfTaskExists(taskId, topologyName);
        killService.killTask(Long.parseLong(taskId), info);
        return ResponseEntity.ok("The task was killed because of " + info);
    }

    /**
     * Common method for submit/restart task. Mode is given in restart parameter
     * @param task Task to process to
     * @param topologyName Name of processing topology
     * @param authorizationHeader Header for authorisation
     * @param restart Mode (submit = <code>false</code> / restart = <code>true</code>) flag
     * @return Respons for rest call
     * @throws AccessDeniedOrTopologyDoesNotExistException
     * @throws DpsTaskValidationException
     * @throws IOException
     */
    private ResponseEntity<Void> doSubmitTask(
            final HttpServletRequest request,
            final DpsTask task, final String topologyName,
            final String authorizationHeader, final boolean restart)
            throws AccessDeniedOrTopologyDoesNotExistException, DpsTaskValidationException, IOException {

        ResponseEntity<Void> result = null;

        final Date sentTime = new Date();
        final String taskJSON = new ObjectMapper().writeValueAsString(task);

        if (task != null) {
            try {
                LOGGER.info(!restart ? "Submitting task" : "Restarting task");
                assertContainTopology(topologyName);
                validateTask(task, topologyName);
                validateOutputDataSetsIfExist(task);
                task.addParameter(PluginParameterKeys.AUTHORIZATION_HEADER, authorizationHeader);
                TaskStatusChecker.init(taskInfoDAO);

                URI responseURI  = buildTaskURI(request.getRequestURL(), task);
                result = ResponseEntity.created(responseURI).build();

                insertTask(task.getTaskId(), topologyName, 0, TaskState.PROCESSING_BY_REST_APPLICATION.toString(),
                        "The task is in a pending mode, it is being processed before submission", sentTime, taskJSON, "");
                permissionManager.grantPermissionsForTask(String.valueOf(task.getTaskId()));


                SubmitTaskParameters parameters = SubmitTaskParameters.builder()
                        .task(task)
                        .topologyName(topologyName)
                        .authorizationHeader(authorizationHeader)
                        .restart(restart).build();

                submitTaskService.submitTask(parameters);

            } catch(DpsTaskValidationException | AccessDeniedOrTopologyDoesNotExistException e) {
                throw e;
            } catch(Exception e) {
                result = getResponseForException(e, "Task submission failed. Internal server error.",
                        HttpStatus.INTERNAL_SERVER_ERROR, task, topologyName, sentTime, taskJSON);
            }
        } else {
            LOGGER.error("Task submission failed. Internal server error. DpsTask task is null.");
            result = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return result;
    }

    /**
     * Inserts/update given task in db. Two tables are modified {@link CassandraTablesAndColumnsNames#BASIC_INFO_TABLE}
     * and {@link CassandraTablesAndColumnsNames#TASKS_BY_STATE_TABLE}<br/>
     * NOTE: Operation is not in transaction! So on table can be modified but second one not
     * Parameters corresponding to names of column in table(s)
     *
     * @param taskId Taski to submit to identifier
     * @param topologyName Name of processing topology
     * @param expectedSize Expected size for task (number of subitems)
     * @param state Current task state
     * @param info Additional information
     * @param sentTime Time of sending task
     * @param taskJSON Taske represented in json format for future use
     */
    private void insertTask(long taskId, String topologyName, int expectedSize, String state, String info, Date sentTime, String taskJSON, String topicName) {
        taskInfoDAO.insert(taskId, topologyName, expectedSize, state, info, sentTime, taskJSON);
        tasksByStateDAO.delete(TaskState.PROCESSING_BY_REST_APPLICATION.toString(), topologyName, taskId);
        tasksByStateDAO.insert(state, topologyName, taskId, applicationIdentifier, topicName);
    }


    private ResponseEntity<Void> getResponseForException(Exception exception, String loggedMessage, HttpStatus httpStatus,
                                                  DpsTask task, String topologyName, Date sentTime, String taskJSON) {
        LOGGER.error(loggedMessage);
        ResponseEntity<Void> response = ResponseEntity.status(httpStatus).build();
        taskInfoDAO.insert(task.getTaskId(), topologyName, 0,
                TaskState.DROPPED.toString(), exception.getMessage(), sentTime, taskJSON);
        return response;
    }


    private URI buildTaskURI(StringBuffer base, DpsTask task) throws URISyntaxException {
        if(base.charAt(base.length()-1) != '/') {
            base.append('/');
        }
        base.append(task.getTaskId());
        return new URI(base.toString());
   }

    private void validateOutputDataSetsIfExist(DpsTask task) throws DpsTaskValidationException {
        List<String> dataSets = readDataSetsList(task.getParameter(PluginParameterKeys.OUTPUT_DATA_SETS));
        for (String dataSetURL : dataSets) {
            try {
                DataSet dataSet = parseDataSetURl(dataSetURL);
                dataSetServiceClient.getDataSetRepresentationsChunk(dataSet.getProviderId(), dataSet.getId(), null);
                validateProviderId(task, dataSet.getProviderId());
            } catch (MalformedURLException e) {
                throw new DpsTaskValidationException("Validation failed. This output dataSet " + dataSetURL
                        + " can not be submitted because: " + e.getMessage());
            } catch (DataSetNotExistsException e) {
                throw new DpsTaskValidationException("Validation failed. This output dataSet " + dataSetURL
                        + " Does not exist");
            } catch (Exception e) {
                throw new DpsTaskValidationException("Unexpected exception happened while validating the dataSet: "
                        + dataSetURL + " because of: " + e.getMessage());
            }
        }
    }

    private void validateProviderId(DpsTask task, String providerId) throws DpsTaskValidationException {
        String providedProviderId = task.getParameter(PluginParameterKeys.PROVIDER_ID);
        if (providedProviderId != null)
            if (!providedProviderId.equals(providerId))
                throw new DpsTaskValidationException("Validation failed. The provider id: " + providedProviderId
                        + " should be the same provider of the output dataSet: " + providerId);
    }

    private List<String> readDataSetsList(String listParameter) {
        return listParameter == null ?
                Arrays.asList() :
                Arrays.asList(listParameter.split(","));
    }

    private DataSet parseDataSetURl(String url) throws MalformedURLException {
        UrlParser parser = new UrlParser(url);
        if (parser.isUrlToDataset()) {
            DataSet dataSet = new DataSet();
            dataSet.setId(parser.getPart(UrlPart.DATA_SETS));
            dataSet.setProviderId(parser.getPart(UrlPart.DATA_PROVIDERS));
            return dataSet;
        }
        throw new MalformedURLException("The dataSet URL is not formulated correctly");

    }

    private void assertContainTopology(String topology) throws AccessDeniedOrTopologyDoesNotExistException {
        if (!topologyManager.containsTopology(topology)) {
            throw new AccessDeniedOrTopologyDoesNotExistException("The topology doesn't exist");
        }
    }

    private void validateTask(DpsTask task, String topologyName) throws DpsTaskValidationException {
        String taskType = specifyTaskType(task, topologyName);
        DpsTaskValidator validator = DpsTaskValidatorFactory.createValidator(taskType);
        validator.validate(task);
    }

    private String specifyTaskType(DpsTask task, String topologyName) throws DpsTaskValidationException {
        if (task.getDataEntry(FILE_URLS) != null) {
            return topologyName + "_" + FILE_URLS.name().toLowerCase();
        }
        if (task.getDataEntry(DATASET_URLS) != null) {
            return topologyName + "_" + DATASET_URLS.name().toLowerCase();
        }
        if (task.getDataEntry(REPOSITORY_URLS) != null) {
            return topologyName + "_" + REPOSITORY_URLS.name().toLowerCase();
        }
        throw new DpsTaskValidationException("Validation failed. Missing required data_entry");
    }
}