package com.rackspace.jenkins_nodepool;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class listens to Jenkins "Computer" events as a means to start a background thread that will cleanup
 * used or orphaned NodePool slaves.
 */
@Extension
public class JanitorialListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(JanitorialListener.class.getName());
    private static final String ENABLED = "nodepool.janitor.enabled";

    private static Thread janitorThread;

    /**
     * A simple lock object used to ensure that the janitor thread is initialized only once.
     */
    private static final Object lock = new Object();

    /**
     * Start the Janitor thread when the master node comes online
     *
     * @param c Computer that is coming online
     * @param listener a task listener
     * @throws IOException if an error occurs while bringing the janitor online
     * @throws InterruptedException if an error occurs while bringing the janitor online
     */
    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        if (c instanceof Jenkins.MasterComputer) {
            startJanitor();
        }

    }

    /**
     * Start the Janitor Thread
     */
    public void startJanitor() {
        LOGGER.log(Level.INFO, "Initializing janitor thread");
        synchronized (lock) {
            if (janitorThread == null) {
                janitorThread = new Thread(new Janitor(), "Nodepool Janitor Thread");

                if (enabled()) {
                    janitorThread.start();
                } else {
                    LOGGER.log(Level.INFO, "Janitor thread is disabled by configuration and will *not* start");
                }
            }
        }
    }

    private boolean enabled() {
        final String enabledStr = System.getProperty(ENABLED);
        if (enabledStr == null) {
            return true;
        } else {
            return Boolean.valueOf(enabledStr);
        }
    }
}
