package com.rackspace.jenkins_nodepool;

import org.junit.*;

import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * A test class for the Utils class.
 *
 * @author Rackspace
 */
public class UtilsTest {

    private static final Pattern alphaNumericPattern = Pattern.compile("^[a-zA-Z0-9_]*$");

    public UtilsTest() {

    }

    @BeforeClass
    public static void setUpClass() {
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void before() {
    }

    @After
    public void after() {
    }

    /**
     * Test strip node label.
     */
    @Test
    public void testStripNodeLabel() {

        // Input test data - add more examples here if you can think of any
        final String[] testLabels = new String[]{
                "nodepool-dd-ubuntu-xenial",
                "nodepool-dd-ubuntu-xenial",
                "nodepool-dd-ubuntu-xenial",
                "nodepool-dd-ubuntu-xenial&&ubuntu-openjdk-8",
                "ubuntu-openjdk-8&&nodepool-dd-ubuntu-xenial",
                "(nodepool-dd-ubuntu-xenial)",
                "(nodepool-dd-ubuntu-xenial&&ubuntu-openjdk-8)",
                "(nodepool-dd-ubuntu-xenial && ubuntu-openjdk-8)",
                "(nodepool-dd-ubuntu-xenial || ubuntu-openjdk-8)",
                "(((nodepool-dd-ubuntu-xenial || ubuntu-openjdk-8)))",
                "(nodepool-dd-ubuntu-xenial || ubuntu-openjdk-8 && foo)",
                "(nodepool-dd-ubuntu-xenial || (ubuntu-openjdk-8 && foo))"
        };

        // For each label, test with the specified label prefix and expected values
        String labelPrefix = "nodepool-dd-";
        String expected = "nodepool-dd-ubuntu-xenial";
        for (String label : testLabels) {
            testStringNodeWithLabel(label, labelPrefix, expected);
        }

        // For each label, test with the specified label prefix and expected value
        labelPrefix = "nodepool-bad-";
        for (String label : testLabels) {
            testStringNodeWithLabelNull(label, labelPrefix);
        }
    }

    private void testStringNodeWithLabel(final String label, final String labelPrefix, final String expected) {
        assertEquals("Test with Label: " + label, expected, Utils.stripNodeLabel(label, labelPrefix));
    }

    private void testStringNodeWithLabelNull(final String label, final String labelPrefix) {
        assertNull("Test with Label: " + label, Utils.stripNodeLabel(label, labelPrefix));
    }

    /**
     * Test Null Label argument.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStripNodeLabelWithNull() {
        final String label = null;
        final String labelPrefix = "nodepool-dd-";
        Utils.stripNodeLabel(label, labelPrefix);
        fail("Should have thrown an IllegalArgumentException");
    }

    /**
     * Test Null Label argument.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStripNodeLabelPrefixWithNull() {
        final String label = "nodepool-dd-ubuntu-xenial";
        final String labelPrefix = null;
        Utils.stripNodeLabel(label, labelPrefix);
        fail("Should have thrown an IllegalArgumentException");
    }
}
