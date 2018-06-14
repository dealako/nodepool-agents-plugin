/*
 * The MIT License
 *
 * Copyright 2018 hughsaunders.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.rackspace.jenkins_nodepool;

import com.google.common.collect.Iterators;
import hudson.Extension;
import hudson.model.*;
import hudson.plugins.sshslaves.verifiers.ManuallyProvidedKeyVerificationStrategy;
import hudson.slaves.RetentionStrategy;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Representation of a Jenkins slave sourced from NodePool.
 *
 * @author hughsaunders
 */
public class NodePoolSlave extends Slave {

    /**
     * Class logger
     */
    private static final Logger LOG = Logger.getLogger(NodePoolSlave.class.getName());
    private static final String FORCE_HOLD_PROPERTY = "nodepool.slave.forcehold";
    private static final int MAX_HOLD_REASON_LEN = 256;

    /**
     * The node from the associated NodePool cluster.
     */
    private transient final NodePoolNode nodePoolNode;

    /**
     * Whether to continue to hold this node after the job is completed.
     */
    private boolean held = false;

    /**
     * Reason for the hold when hold is enabled
     */
    private String holdReason = "";

    /**
     * The user who is requesting the hold.
     */
    private String holdUser = null;

    /**
     * Increment this when modifying this class.
     */
    static final long serialVersionUID = 1L;

    /**
     * Create a new slave
     *
     * @param nodePoolNode  the node from NodePool
     * @param credentialsId the Jenkins credential identifier
     * @throws Descriptor.FormException on configuration exception
     * @throws IOException              on configuration exception
     */
    @DataBoundConstructor  // not used, but it makes stapler happy if you click "Save" while editing a Node.
    public NodePoolSlave(NodePoolNode nodePoolNode, String credentialsId) throws Descriptor.FormException, IOException {
        super(
                nodePoolNode.getName(), // name
                "Nodepool Node", // description
                "/var/lib/jenkins", // TODO this should be the path to the root of the workspace on the slave
                "1", // num executors
                Mode.EXCLUSIVE,
                nodePoolNode.getJenkinsLabel(),
                new NodePoolSSHLauncher(
                        nodePoolNode.getHost(),
                        nodePoolNode.getPort(),
                        credentialsId,
                        "", //jvmoptions
                        determineJDKInstaller(nodePoolNode.getNodePool()), //jdkInstaller
                        "", //prefixStartSlaveCmd
                        "", //suffixStartSlaveCmd
                        300, //launchTimeoutSeconds
                        30, //maxNumRetries
                        10, //retryWaitTime
                        new ManuallyProvidedKeyVerificationStrategy(nodePoolNode.getHostKey())
                ),
                new RetentionStrategy.Always(), //retentionStrategy
                new ArrayList() //nodeProperties
        );

        this.nodePoolNode = nodePoolNode;
    }

    /**
     * A quick function to determine which JDK installer we have based on the NodePool configuration.
     *
     * @param np a nodepool object reference
     * @return a NodePool JDK installer instance
     */
    private static NodePoolJDKInstaller determineJDKInstaller(final NodePool np) {
        final String jdkInstallationScript = np.getJdkInstallationScript();

        // If we are not provided a script or if the value is empty, use a default - otherwise use the script installer
        NodePoolJDKInstaller installer;
        if (jdkInstallationScript == null || jdkInstallationScript.trim().isEmpty()) {
            installer = new UbuntuOpenJDKHeadlessInstaller();
        } else {
            installer = new NodePoolJDKScriptInstaller(np.getJdkInstallationScript().trim(), np.getJdkHome());
        }

        LOG.log(Level.INFO, String.format("Using JDK Installer:  %s", installer.getClass().getSimpleName()));
        return installer;
    }

    public NodePoolNode getNodePoolNode() {
        return nodePoolNode;
    }

    // Called for deserialisation
    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject(); // call default deserializer
        LOG.log(Level.WARNING, "Removing NodePool Slave {0} on startup as its a nodepool node that will have been destroyed", this.toString());
        Jenkins.getInstance().removeNode(this);
    }

    /**
     * Returns a new computer based on the nodepool node object associated with this class instance.
     *
     * @return a new computer based on the nodepool node object associated with this class instance.
     */
    @Override
    public Computer createComputer() {
        return new NodePoolComputer(this, nodePoolNode);
    }

    /**
     * This gets invoked when the user clicks "Save" while editing the "Node" in the UI.
     *
     * @param req  stapler request
     * @param form form data to update slave with
     * @return new slave object
     * @throws Descriptor.FormException if things go sideways
     */
    @Override
    public Node reconfigure(final StaplerRequest req, JSONObject form) throws Descriptor.FormException {
        // update `this` with submitted data from the form and return it.  the superclass version in Node creates a new
        // slave object, which we don't need.
        if (form == null) {
            return null;
        }

        final boolean held = form.getBoolean("held");
        setHeld(held);
        LOG.log(Level.INFO, "Set node hold to: " + isHeld());

        // Set the number of executors from the form value
        setNumExecutors(form.getInt("numExecutors"));

        // Set the hold reason
        final String holdReason = form.getString("holdReason");
        if (holdReason != null) {
            final String trimmedHoldReason = holdReason.substring(0, Math.min(holdReason.length(), MAX_HOLD_REASON_LEN));
            setHoldReason(trimmedHoldReason);
            LOG.log(Level.FINE, "Set hold reason: " + getHoldReason());
        }

        if (isHeld()) {
            if (getHoldUser() == null) {
                setHoldUser(form.getString("holdUser"));
                LOG.log(Level.FINE, "Node held: " + isHeld() + " with user: " + getHoldUser() + " which was previously null.");
            } else {
                LOG.log(Level.FINE, "Node held: " + isHeld() + " with previous user: " + getHoldUser());
            }
        } else {
            setHoldUser(null);
            LOG.log(Level.FINE, "Node not held: " + isHeld() + ", setting user: " + getHoldUser());
        }

        return this;
    }

    /**
     * If true, hold onto the node after the associated build is completed.  Do not release it to NodePool to get
     * cleaned up.
     *
     * @return true if node should be held.
     */
    public boolean isHeld() {
        // check for optional "force hold" property, primarily useful for debugging.
        final String forceHoldStr = System.getProperty(FORCE_HOLD_PROPERTY, "false");
        final boolean forceHold = Boolean.valueOf(forceHoldStr);

        return held || forceHold;
    }

    /**
     * Set to true in order to hold the node after the build is completed.
     *
     * @param held whether to hold the node after the build completes
     */
    public void setHeld(boolean held) {
        this.held = held;
    }

    /**
     * Returns the hold reason value.
     *
     * @return the value of the hold reason - typically a justification for the hold.
     */
    public String getHoldReason() {
        return holdReason;
    }

    /**
     * Sets the hold reason value.
     *
     * @param holdReason the hold reason -  typically a justification for the hold.
     */
    public void setHoldReason(final String holdReason) {
        this.holdReason = holdReason;
    }

    /**
     * Returns the user requesting the hold.
     *
     * @return the user requesting the hold.
     */
    public String getHoldUser() {
        return holdUser;
    }

    /**
     * Sets the user id for the user requesting the node hold.
     *
     * @param holdUser the user id of the user requesting the node hold
     */
    public void setHoldUser(String holdUser) {
        this.holdUser = holdUser;
    }

    @Override
    public SlaveDescriptor getDescriptor() {
        final NodePoolSlaveDescriptor descriptor = new NodePoolSlaveDescriptor();
        descriptor.setNodePoolSlave(this);
        return descriptor;
    }

    /**
     * Test if the slave's build is done.  A slave is considered complete when we've executed at least one build and all
     * the executors are idle, otherwise we are not done.
     *
     * @return true if the build associated with this slave has completed and the executors for the build are idle.
     */
    boolean isBuildComplete() {

        final NodePoolComputer computer = (NodePoolComputer) toComputer();
        if (computer == null) {
            return false;
        }

        final RunList builds = computer.getBuilds();

        // DEBUG
        // printBuildDetails(builds);
        // printExecutorDetails(computer.getAllExecutors());
        // DEBUG END

        // The following test is a bit complex due to the fact that we have a separate Janitor worker that is
        // responsible for cleaning up previously used nodes.  Once the node is handed to Jenkins the Janitor
        // periodically checks the status.  In this test, we're trying to determine:
        //   1) if it has a build assigned to it
        //   2) the executor as attempted to run the job and is now finished/idle

        // If we've executed at least one build and all the executors are idle and we actually ran something...
        // ...then we must be done, otherwise we are not done
        if (Iterators.size(builds.iterator()) > 0 && isAllExecutorsIdle(computer.getAllExecutors())) {
            LOG.log(Level.FINE, "Slave " + this + " started and now idle." +
                    "Builds: " + Iterators.size(builds.iterator()) +
                    ", Started: " + isExecutorStarted(computer.getAllExecutors()) +
                    ", Idle: " + isAllExecutorsIdle(computer.getAllExecutors()) +
                    ". Must be done.");
            return true;
        } else {
            // If we reach this point, either we don't have any builds or at least one executor has yet to return to idle status.
            LOG.log(Level.FINE, "Slave " + this + " build is not complete. " +
                    "Builds: " + Iterators.size(builds.iterator()) +
                    ", Started: " + isExecutorStarted(computer.getAllExecutors()) +
                    ", Idle: " + isAllExecutorsIdle(computer.getAllExecutors())
            );
            return false;
        }
    }

    /**
     * Debug routine to print the build details.
     *
     * @param builds a run list with all the builds
     */
    private void printBuildDetails(RunList builds) {
        for (Object obj : builds) {
            final Run build = (Run) obj;
            LOG.log(Level.FINE, "Build: " + build.getDisplayName() +
                    ", id: " + build.getId() +
                    ", number: " + build.number +
                    ", queueId: " + build.getQueueId() +
                    ", result: " + build.getResult() +
                    ", durationStr: " + build.getDurationString() +
                    ", duration: " + build.getDuration() +
                    ", timestampStr: " + build.getTimestampString() +
                    ", getWhyKeepLog: " + build.getWhyKeepLog() +
                    ", startTimeInMillis: " + build.getStartTimeInMillis() +
                    ", timeInMillis: " + build.getTimeInMillis() +
                    ", now-startTimeInMillis: " + (System.currentTimeMillis() - build.getStartTimeInMillis())
            );
        }
    }

    /**
     * Debug routine to print the executor details.
     *
     * @param executorList a list of executors associated with this node
     */
    private void printExecutorDetails(final List<Executor> executorList) {
        for (final Executor executor : executorList) {
            LOG.log(Level.FINE, "Executor: " + executor.getDisplayName() +
                    ", workUnit: " + executor.getCurrentWorkUnit() +
                    ", isIdle: " + executor.isIdle() +
                    ", isThreadAlive: " + executor.isAlive() +
                    ", threadState: " + executor.getState() +
                    ", isActive: " + executor.isActive() +
                    ", isBusy: " + executor.isBusy() +
                    ", isLikelyStuck: " + executor.isLikelyStuck() +
                    ", isParking: " + executor.isParking() +
                    ", isDisplayCell: " + executor.isDisplayCell() +
                    ", isDaemon: " + executor.isDaemon() +
                    ", idleStartMilliseconds: " + executor.getIdleStartMilliseconds() +
                    ", timestampString: " + executor.getTimestampString() +
                    ", elapsedTime: " + executor.getElapsedTime()
            );
        }
    }

    /**
     * Convenience method to determine if all executors are idle. Returns true if all executors are idle, false otherwise.
     *
     * @param executorList a list of executors
     * @return true if all executors are idle, false otherwise.
     */
    private boolean isAllExecutorsIdle(final List<Executor> executorList) {

        // Flag to indicate if all the executors are idle
        boolean allExecutorsIdle = true;

        for (final Executor executor : executorList) {
            // If one of the executors are not idle - set the flag, we're done
            if (!executor.isIdle()) {
                allExecutorsIdle = false;
                break;
            }
        }

        return allExecutorsIdle;
    }

    /**
     * Returns true if one of the executor's threads have started running, false otherwise.
     *
     * @param executorList a list of executors
     * @return true if one of the executor's threads have started running, false otherwise.
     */
    private boolean isExecutorStarted(final List<Executor> executorList) {

        // Flag to indicate if all of the executor work units are empty
        boolean executorStarted = false;

        for (final Executor executor : executorList) {

            // If one of the executors is NOT in a new state, then it's transition from NEW to something else
            // NEW == Thread state for a thread which has not yet started.
            if (executor.getState() != Thread.State.NEW) {
                executorStarted = true;
                break;
            }
        }

        return executorStarted;
    }

    /**
     * It makes jelly rendering on the node configuration page happy to have this defined.
     * <p>
     * One thing this is used for is locating the help file associated with the form field(s).
     */
    @Extension
    public static final class NodePoolSlaveDescriptor extends SlaveDescriptor {

        private NodePoolSlave nodePoolSlave;

        public void setNodePoolSlave(NodePoolSlave slave) {
            this.nodePoolSlave = slave;
        }

        public NodePoolSlave getNodePoolSlave() {
            return this.nodePoolSlave;
        }

        @Override
        public String getDisplayName() {
            return "NodePool Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
