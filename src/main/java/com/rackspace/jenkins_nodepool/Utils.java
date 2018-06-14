package com.rackspace.jenkins_nodepool;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {
    /**
     * Our class logger.
     */
    private static final Logger LOG = Logger.getLogger(Utils.class.getName());

    /**
     * A pattern for valid labels
     */
    private static final Pattern patternLabel = Pattern.compile("([A-Za-z0-9-_]+)");

    /**
     * Private constructor prevents instantiation.
     */
    private Utils() {

    }

    /**
     * When provided a simple or complex Jenkins label this method returns the first corresponding label that matches
     * the labelPrefix. If none are found to match it returns null.  Examples of a simple label would be:
     * nodepool-dd-ubuntu-xenial and a complex label might look like: ubuntu-openjdk-8 &amp;&amp; nodepool-dd-ubuntu-xenial
     *
     * @param label the simple or complex label string
     * @param labelPrefix the label prefix
     * @return the first occurence of the label that matches the prefix string
     */
    public static String stripNodeLabel(final String label, final String labelPrefix) {
        if (label == null) {
            throw new IllegalArgumentException("Label is null");
        }

        if (labelPrefix == null || labelPrefix.isEmpty()) {
            throw new IllegalArgumentException("Label Prefix is null or empty");
        }

        final Matcher m = patternLabel.matcher(label);

        // Find all the matches within the label - should match one or more labels
        // For each label that matched our regex...should be 1 or mre
        while (m.find()) {
            final String theLabel = m.group();
            if (theLabel.contains(labelPrefix)) {
                return theLabel;
            }
        }

        return null;
    }
}
