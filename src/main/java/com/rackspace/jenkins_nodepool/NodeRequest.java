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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Label;
import hudson.model.Queue.Task;
import org.apache.zookeeper.CreateMode;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a nodepool node request. Data format is JSON dump of following
 * dict structure: --- node_types: - label1 - label2 requestor: string id (eg
 * hostname) state: string requested|pending|fulfilled|failed state_time: float
 * seconds since epoch
 *
 * @author hughsaunders
 */
public class NodeRequest extends ZooKeeperObject {

    //TODO: check requests-lock znodes, they seem to be stacking up.
    // what creates them and what should clean them up?
    private static final Logger LOGGER = Logger.getLogger(NodeRequest.class.getName());

    /**
     * Request start time.
     */
    private final Long startTime;

    /**
     * Associatd Jenkins task that triggered this request
     */
    private final Task task;

    /**
     * Create new request
     *
     * @param nodePool  NodePool cluster to use
     * @param task  Associated Jenkins task/build
     * @throws Exception  on ZooKeeper error
     */
    @SuppressFBWarnings
    public NodeRequest(NodePool nodePool, Task task) throws Exception {
        super(nodePool);
        final String jenkinsLabel = task.getAssignedLabel().getDisplayName();
        final List<String> node_types = new ArrayList();
        node_types.add(nodePool.nodePoolLabelFromJenkinsLabel(jenkinsLabel));
        data.put("node_types", node_types);
        data.put("requestor", nodePool.getRequestor());
        data.put("state", RequestState.requested);
        data.put("state_time", new Double(System.currentTimeMillis() / 1000));
        data.put("jenkins_label", jenkinsLabel);
        // sets path and zkid
        createZNode();
        startTime = System.currentTimeMillis();
        this.task = task;
    }

    /**
     * Create the ZNode associated with this node request
     *
     * @throws Exception  on ZooKeeper error
     */
    @Override
    public void createZNode() throws Exception {
        final String createPath = MessageFormat.format("/{0}/{1}-",
                nodePool.getRequestRoot(), nodePool.getPriority());
        LOGGER.finest(MessageFormat.format("Creating request node: {0}",
                createPath));
        final String requestPath = nodePool.getConn().create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                .forPath(createPath, getJson().getBytes(nodePool.getCharset()));

        LOGGER.log(Level.FINEST, "Requeste created at path: {0}", requestPath);

        setZKID(nodePool.idForPath(requestPath));
        setPath(requestPath);
    }

    public RequestState getState() {
        return (RequestState) data.get("state");
    }

    /**
     * Get node names only from the local cache of the ZNode
     *
     * @return list of node names
     */
    public List<String> getAllocatedNodeNames() {
        List<String> nodes = (List<String>)data.get("nodes");
        if (nodes == null) {
            nodes = Collections.emptyList();
        }
        return nodes;
    }

    /**
     * Get list of NodePool nodes that have been allocated to fulfill this request
     *
     * @return  list of nodes
     * @throws Exception on ZooKeeper error
     */
    public List<NodePoolNode> getAllocatedNodes() throws Exception {
        // Example fulfilled request
        // {"nodes": ["0000000000"], "node_types": ["debian"], "state": "fulfilled", "declined_by": [], "state_time": 1520849225.4513698, "reuse": false, "requestor": "NodePool:min-ready"}

        // Refresh our view of the data
        updateFromZK();

        if (data.get("state") != RequestState.fulfilled) {
            throw new IllegalStateException("Attempt to get allocated nodes from a node request before it has been fulfilled");
        }
        final List<NodePoolNode> nodeObjects = new ArrayList<>();
        for (Object id : (List) data.get("nodes")) {
            nodeObjects.add(new NodePoolNode(nodePool, (String) id));
        }
        return nodeObjects;
    }

    /**
     * Update the local copy of the request data from values source from ZooKeeper
     *
     * @param newData  map of data values from ZooKeeper
     */
    @Override
    public void updateFromMap(Map newData) {
        super.updateFromMap(newData);
        // convert state time from string
        final Double stateTime = (Double) newData.get("state_time");
        data.put("state_time", stateTime);

        // convert 'state' back into its corresponding enum value
        final String stateString = (String) newData.get("state");
        data.put("state", RequestState.valueOf(stateString));
    }

    /**
     * Returns the node pool label for the node request.
     *
     * @return the node pool label
     */
    public String getNodePoolLabel() {
        final List<String> labels = (List<String>) data.get("node_types");
        return labels.get(0);
    }

    /**
     * Returns the jenkins label for this node request.
     *
     * @return the jenkins label
     */
    public Label getJenkinsLabel() {
        return task.getAssignedLabel();
    }

    public String getAge() {
        final Duration d = Duration.ofMillis(System.currentTimeMillis() - startTime);
        long s = d.getSeconds();
        if (s < 60) {
            return MessageFormat.format("{0}s", d.getSeconds());
        } else {
            return MessageFormat.format("{0}m", d.getSeconds() / 60);
        }
    }

    public Task getTask() {
        return task;
    }
}
