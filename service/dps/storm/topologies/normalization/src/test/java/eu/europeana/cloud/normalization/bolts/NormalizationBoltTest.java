package eu.europeana.cloud.normalization.bolts;

import eu.europeana.cloud.service.dps.PluginParameterKeys;
import eu.europeana.cloud.service.dps.storm.StormTaskTuple;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static eu.europeana.cloud.service.dps.test.TestConstants.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;

public class NormalizationBoltTest {

    @Mock(name = "outputCollector")
    private OutputCollector outputCollector;

    @Captor
    ArgumentCaptor<Values> captor = ArgumentCaptor.forClass(Values.class);

    @InjectMocks
    private NormalizationBolt normalizationBolt = new NormalizationBolt();

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void shouldNormalizeRecord() throws Exception {
        //given
        Tuple anchorTuple = mock(TupleImpl.class);
        byte[] inputData = Files.readAllBytes(Paths.get("src/test/resources/edm.xml"));
        byte[] expected = Files.readAllBytes(Paths.get("src/test/resources/normalized.xml"));
        normalizationBolt.prepare();

        //when
        normalizationBolt.execute(anchorTuple, getCorrectStormTuple(inputData));

        //then
        Mockito.verify(outputCollector, Mockito.times(1)).emit(any(Tuple.class), captor.capture());
        Values capturedValues = captor.getValue();
        Assert.assertEquals(new String(expected), new String((byte[]) capturedValues.get(3)).replaceAll("\r", ""));
    }


    @Test
    public void shouldEmitErrorWhenNormalizationResultContainsErrorMessage() throws Exception {
        //given
        Tuple anchorTuple = mock(TupleImpl.class);
        byte[] inputData = Files.readAllBytes(Paths.get("src/test/resources/edm-not-valid.xml"));
        normalizationBolt.prepare();

        //when
        normalizationBolt.execute(anchorTuple, getCorrectStormTuple(inputData));

        //then
        Mockito.verify(outputCollector, Mockito.times(1)).emit(Mockito.anyString(), any(Tuple.class), captor.capture());
        Values capturedValues = captor.getValue();
        Map val = (Map) capturedValues.get(2);
        Assert.assertEquals("Error during normalization.", val.get("additionalInfo"));
        Assert.assertTrue(val.get("info_text").toString().startsWith("Error parsing XML: Could not parse DOM for"));
    }


    @Test
    public void shouldEmitErrorWhenCantPrepareTupleForEmission() throws Exception {
        //given
        Tuple anchorTuple = mock(TupleImpl.class);
        byte[] inputData = Files.readAllBytes(Paths.get("src/test/resources/edm.xml"));
        normalizationBolt.prepare();

        //when
        normalizationBolt.execute(anchorTuple, getMalformedStormTuple(inputData));

        //then
        Mockito.verify(outputCollector, Mockito.times(1)).emit(Mockito.anyString(), any(Tuple.class), captor.capture());
        Values capturedValues = captor.getValue();
        Map val = (Map) capturedValues.get(2);
        Assert.assertTrue(val.get("additionalInfo").toString().contains("Cannot prepare output storm tuple."));
        Assert.assertTrue(val.get("additionalInfo").toString().contains("malformed.url"));
    }

    private StormTaskTuple getCorrectStormTuple(byte[] inputData) {
        return getStormTuple(SOURCE_VERSION_URL, inputData);
    }

    private StormTaskTuple getMalformedStormTuple(byte[] inputData) {
        return getStormTuple("malformed.url", inputData);
    }

    private StormTaskTuple getStormTuple(String fileUrl, byte[] inputData) {
        StormTaskTuple tuple = new StormTaskTuple(123, "TASK_NAME", fileUrl, inputData, prepareStormTaskTupleParameters(), null);
        return tuple;
    }

    private HashMap<String, String> prepareStormTaskTupleParameters() {
        HashMap<String, String> parameters = new HashMap<>();
        parameters.put(PluginParameterKeys.MESSAGE_PROCESSING_START_TIME_IN_MS, "1");
        return parameters;
    }
}