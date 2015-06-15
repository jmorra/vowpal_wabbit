package vw;

import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.*;

import static org.junit.Assert.*;

/**
 * Created by jmorra on 11/24/14.
 */
public class VWTest {

    /**
     * Model created with paste -d '\n' <(jot -b '-1 |' 100) <(jot -b '1 |' 100) | vw --quiet --loss_function logistic --link logistic -f logistic.test.model
     */
    private static final String LogisticModelPath = "src/test/resources/logistic.test.model";

    private String houseModel;
    private final String heightData = "|f height:0.23 weight:0.25 width:0.05";
    private VW houseScorer;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setup() throws IOException {
        // Since we want this test to continue to work between VW changes, we can't store the model
        // Instead, we'll make a new model for each test
        houseModel = temporaryFolder.newFile().getAbsolutePath();
        String[] houseData = new String[]{
                "0 | price:.23 sqft:.25 age:.05 2006",
                "1 2 'second_house | price:.18 sqft:.15 age:.35 1976",
                "0 1 0.5 'third_house | price:.53 sqft:.32 age:.87 1924"};
        VW learner = new VW(" --quiet -f " + houseModel);
        for (String d : houseData) {
            learner.learn(d);
        }
        learner.close();
        houseScorer = new VW("--quiet -t -i " + houseModel);
    }

    @After
    public void cleanup() {
        houseScorer.close();
    }

    private long streamingLoadTest(int times) {
        VW m1 = new VW("--quiet");
        long start = System.currentTimeMillis();
        for (int i=0; i<times; ++i) {
            // This will force a new string to be created every time for a fair test
            m1.learn(heightData + "");
        }
        m1.close();
        return System.currentTimeMillis() - start;
    }

    private long stdLoadTest(int times) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec("../vowpalwabbit/vw --quiet");
        PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(p.getOutputStream())));

        long start = System.currentTimeMillis();
        for (int i=0; i<times; ++i) {
            writer.println(heightData);
        }
        writer.close();
        p.waitFor();
        return System.currentTimeMillis() - start;
    }

    @Test
    @Ignore
    public void loadTest() throws IOException, InterruptedException {
        int times = (int)1e6;

        System.out.println("Milliseconds for JNI layer: " + streamingLoadTest(times));
        System.out.println("Milliseconds for external process: " + stdLoadTest(times));
    }

    @Test
    public void testBlank() {
        float prediction = houseScorer.predict("| ");
        assertEquals(0.075, prediction, 0.001);
    }

    @Test
    public void testLine() {
        float prediction1 = houseScorer.predict("| price:0.23 sqft:0.25 age:0.05 2006");
        float prediction2 = houseScorer.predict("| price:0.23 sqft:0.25 age:0.05 2006");
        assertEquals(0.118, prediction1, 0.001);
        assertEquals(0.118, prediction2, 0.001);
    }

    @Test
    public void testLearn() {
        VW learner = new VW("--quiet");
        float firstPrediction = learner.learn("0.1 " + heightData);
        float secondPrediction = learner.learn("0.9 " + heightData);
        assertNotEquals(firstPrediction, secondPrediction, 0.001);
        learner.close();
    }

    @Test
    public void testBadVWArgs() {
        final String args = "--BAD_FEATURE___ounq24tjnasdf8h";
        thrown.expect(IllegalArgumentException.class);
        new VW(args + " --quiet");
    }

    @Test
    public void testManySamples() {
        File model = new File("basic.model");
        model.deleteOnExit();
        VW m = new VW("--quiet --loss_function logistic --link logistic -f " + model.getAbsolutePath());
        for (int i=0; i<100; ++i) {
            m.learn("-1 | ");
            m.learn("1 | ");
        }
        m.close();

        float expVwOutput = 0.50419676f;
        m = new VW("--quiet -i " + model.getAbsolutePath());
        assertEquals(expVwOutput, m.predict("| "), 0.0001);
    }

    @Test
    public void twoModelTest() {
        VW m1 = new VW("--quiet");
        VW m2 = new VW("--quiet");

        float a = m1.predict("-1 | ");
        m1.close();
        float b = m2.predict("-1 | ");
        m2.close();
        assertEquals(a, b, 0.000001);
    }

    @Test
    public void testAlreadyClosed() {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage("Already closed.");
        VW s = new VW("--quiet");
        s.close();
        s.predict("1 | ");
    }

    @Test
    @Ignore
    public void testBadModel() {
        // Right now VW seg faults on a bad model.  Ideally we should throw an exception
        // that the Java layer could do something about
        thrown.expect(Exception.class);
        thrown.expectMessage("bad VW model");
        VW vw = new VW("--quiet -i src/test/resources/vw_bad.model");
        vw.close();
    }

    @Test
    public void testContextualBandits() throws IOException {
        // Note that the expected values in this test were obtained by running
        // vw from the command line as follows
        // echo -e "1:2:0.4 | a c\n3:0.5:0.2 | b d\n4:1.2:0.5 | a b c\n2:1:0.3 | b c\n3:1.5:0.7 | a d" | ../vowpalwabbit/vw --cb 4 -f cb.model -p cb.train.out
        // echo -e "1:2 3:5 4:1:0.6 | a c d\n1:0.5 2:1:0.4 3:2 4:1.5 | c d" | ../vowpalwabbit/vw -i cb.model -t -p cb.out
        String[] train = new String[]{
                "1:2:0.4 | a c",
                "3:0.5:0.2 | b d",
                "4:1.2:0.5 | a b c",
                "2:1:0.3 | b c",
                "3:1.5:0.7 | a d"
        };
        String cbModel = temporaryFolder.newFile().getAbsolutePath();
        VW vw = new VW("--quiet --cb 4 -f " + cbModel);
        float[] trainPreds = new float[train.length];
        for (int i=0; i<train.length; ++i) {
            trainPreds[i] = vw.learn(train[i]);
        }
        float[] expectedTrainPreds = new float[]{1, 2, 2, 2, 2};
        vw.close();

        assertArrayEquals(expectedTrainPreds, trainPreds, 0.00001f);

        vw = new VW("--quiet -t -i " + cbModel);
        String[] test = new String[]{
                "1:2 3:5 4:1:0.6 | a c d",
                "1:0.5 2:1:0.4 3:2 4:1.5 | c d"
        };

        float[] testPreds = new float[test.length];
        for (int i=0; i<testPreds.length; ++i) {
            testPreds[i] = vw.predict(test[i]);
        }
        float[] expectedTestPreds = new float[]{4, 4};
        vw.close();
        assertArrayEquals(expectedTestPreds, testPreds, 0.000001f);
    }

    /**
     * Test that we can recover from specifying a link when it is already encoded in the binary VW model file.
     * @throws IOException when issues arise getting model location.
     */
    @Test public void testLinkFunctionRecovery() throws IOException {
        final String example = "";
        final float expected = 0.504197f; // From testing VW on CLI.
        final String modelPath = new File(LogisticModelPath).getCanonicalPath();

        // Without a specified link.
        final float withoutLink = new VW(logisticModelParams(false) + " -i " + modelPath).predict(example);
        assertEquals(expected, withoutLink, 1.e-6);

        // This should not fail
        // With a specified link is the same as without a specified link.
        try {
            final float withLink = new VW(logisticModelParams(true) + " -i " + modelPath).predict(example);
            assertEquals(withoutLink, withLink, 1.e-6);
        }
        catch(Throwable t) {
            fail("Should not throw an exception.");
        }
    }

    private static String logisticModelParams(final boolean withLink) {
        return "--quiet --loss_function logistic " + (withLink ? "--link logistic " : "");
    }
}
