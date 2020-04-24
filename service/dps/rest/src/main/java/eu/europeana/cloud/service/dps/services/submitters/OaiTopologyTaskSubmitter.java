package eu.europeana.cloud.service.dps.services.submitters;

import eu.europeana.cloud.common.model.dps.TaskState;
import eu.europeana.cloud.service.dps.DpsTask;
import eu.europeana.cloud.service.dps.Harvest;
import eu.europeana.cloud.service.dps.HarvestResult;
import eu.europeana.cloud.service.dps.converters.DpsTaskToHarvestConverter;
import eu.europeana.cloud.service.dps.exceptions.TaskSubmissionException;
import eu.europeana.cloud.service.dps.oaipmh.HarvesterException;
import eu.europeana.cloud.service.dps.services.TaskStatusUpdater;
import eu.europeana.cloud.service.dps.structs.SubmitTaskParameters;
import eu.europeana.cloud.service.dps.utils.HarvestsExecutor;
import eu.europeana.cloud.service.dps.utils.KafkaTopicSelector;
import eu.europeana.cloud.service.dps.utils.files.counter.FilesCounter;
import eu.europeana.cloud.service.dps.utils.files.counter.FilesCounterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OaiTopologyTaskSubmitter implements TaskSubmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OaiTopologyTaskSubmitter.class);

    @Autowired
    private HarvestsExecutor harvestsExecutor;

    @Autowired
    private KafkaTopicSelector kafkaTopicSelector;

    @Autowired
    private FilesCounterFactory filesCounterFactory;

    @Autowired
    private TaskStatusUpdater taskStatusUpdater;

    @Override
    public void submitTask(SubmitTaskParameters parameters) throws TaskSubmissionException {

        int expectedCount = getFilesCountInsideTask(parameters.getTask(), parameters.getTopologyName());
        LOGGER.info("The task {} is in a pending mode.Expected size: {}", parameters.getTask().getTaskId(), expectedCount);

        if (expectedCount == 0) {
            taskStatusUpdater.insertTask(parameters.getTask().getTaskId(), parameters.getTopologyName(),
                    expectedCount, TaskState.DROPPED.toString(), "The task doesn't include any records", "");
        }

        String preferredTopicName = kafkaTopicSelector.findPreferredTopicNameFor(parameters.getTopologyName());
        taskStatusUpdater.insertTask(parameters.getTask().getTaskId(), parameters.getTopologyName(),
                expectedCount, TaskState.PROCESSING_BY_REST_APPLICATION.toString(), "Task submitted successfully and processed by REST app", preferredTopicName);

        List<Harvest> harvestsToByExecuted = new DpsTaskToHarvestConverter().from(parameters.getTask());

        try {
            HarvestResult harvesterResult;
            if (!parameters.isRestart()) {
                harvesterResult = harvestsExecutor.execute(parameters.getTopologyName(), harvestsToByExecuted, parameters.getTask(), preferredTopicName);
            } else {
                harvesterResult = harvestsExecutor.executeForRestart(parameters.getTopologyName(), harvestsToByExecuted, parameters.getTask(), preferredTopicName);
            }
            updateTaskStatus(parameters.getTask().getTaskId(), harvesterResult);
        } catch (HarvesterException e) {
            throw new TaskSubmissionException("Unable to submit task properly", e);
        }
    }

    /**
     * @return The number of files inside the task.
     */
    private int getFilesCountInsideTask(DpsTask task, String topologyName) throws TaskSubmissionException {
        FilesCounter filesCounter = filesCounterFactory.createFilesCounter(task, topologyName);
        return filesCounter.getFilesCount(task);
    }

    private void updateTaskStatus(long taskId, HarvestResult harvesterResult) {
        if (harvesterResult.getTaskState() != TaskState.DROPPED && harvesterResult.getResultCounter() == 0) {
            LOGGER.info("Task dropped. No data harvested");
            taskStatusUpdater.dropTask(taskId, "The task with the submitted parameters is empty");
        } else {
            LOGGER.info("Updating task {} expected size to: {}", taskId, harvesterResult.getResultCounter());
            taskStatusUpdater.updateStatusExpectedSize(taskId, harvesterResult.getTaskState().toString(),
                    harvesterResult.getResultCounter());
        }
    }
}
