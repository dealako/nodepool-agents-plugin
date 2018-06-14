package com.rackspace.jenkins_nodepool;

import java.text.MessageFormat;
import java.util.List;


/**
 * Representation of a node from NodePool (not necessarily a Jenkins slave)
 */

public class NodePoolNode extends ZooKeeperObject {

    /**
     * The lock on the node ZNode
     */
    final KazooLock lock;

    /**
     * Creates a new Zookeeper node for the node pool.
     *
     * @param nodePool NodePool
     * @param id       id of node as represented in ZooKeeper
     * @throws Exception on ZooKeeper error
     */
    public NodePoolNode(NodePool nodePool, String id) throws Exception {
        super(nodePool);
        super.setPath(String.format("/%s/%s", nodePool.getNodeRoot(), id));
        super.setZKID(id);
        super.updateFromZK();
        this.lock = new KazooLock(getLockPath(), nodePool);
    }

    /**
     * Get labeled type of node, according to NodePool
     *
     * @return NodePool label
     */
    public String getNPType() {
        return (String) data.get("type");
    }

    /**
     * Get path to lock object used to lock this node
     *
     * @return lock path
     */
    final String getLockPath() {
        return MessageFormat.format("/{0}/{1}/lock", nodePool.getNodeRoot(), zKID);
    }

    /**
     * Get Jenkins's version of the node label
     *
     * @return Jenkins label
     */
    public String getJenkinsLabel() {
        return MessageFormat.format("{0}{1}",
                new Object[]{nodePool.getLabelPrefix(), getNPType()});
    }

    public String getName() {
        return MessageFormat.format("{0}-{1}", getJenkinsLabel(), getZKID());
    }

    /**
     * Used to render node editing page in the UI
     *
     * @return node pool object
     */
    public NodePool getNodePool() {
        return nodePool;
    }

    public String getHost() {
        return (String) data.get("interface_ip");
    }

    public Integer getPort() {
        Double port = (Double) data.get("connection_port");
        if (port == null) {
            // fall back to the field name used on older NodePool clusters.
            port = (Double) data.getOrDefault("ssh_port", 22.0);
        }
        return port.intValue();
    }

    public String getHostKey() {
        List<String> hostKeys = (List) data.get("host_keys");
        return hostKeys.get(0);
    }

    public List<String> getHostKeys() {
        return (List) data.get("host_keys");
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Update the state of the node according to  NodePool
     *
     * @param state NodePool node state
     * @throws Exception on ZooKeeper error
     */
    private void setState(String state) throws Exception {
        setState(state, true);
    }

    /**
     * Update the state of the node according to  NodePool
     *
     * @param state NodePool node state
     * @param write if true, save updates back to ZooKeeper
     * @throws Exception on ZooKeeper error
     */
    private void setState(String state, boolean write) throws Exception {
        // get up to date info, so we are less likely to lose data
        // when writing back.
        updateFromZK();
        data.put("state", state);
        if (write) {
            writeToZK();
        }
    }

    /**
     * Mark the node as being held.  A held node must be manually deleted from NodePool.
     *
     * @param jobIdentifier identifier of build/job that this node was running.
     * @throws Exception on ZK error
     */
    public void hold(String jobIdentifier) throws Exception {
        // Lock should already be held, we only hold nodes that have already been assigned to Jenkins.
        setState("hold", false);
        data.put("comment", "Jenkins hold");
        data.put("hold_job", jobIdentifier);
        writeToZK();

        unlock(); // imitate zuul and unlock here.
    }

    public void setInUse() throws Exception {
        lock.acquire();
        setState("in-use");
    }

    /**
     * Mark the node as being used and release it.  It will be destroyed by NodePool.
     *
     * @throws Exception on ZooKeeper error
     */
    public void release() throws Exception {
        setState("used");

        if (lock.getState() == KazooLock.State.LOCKED) {
            unlock();
        }
    }

    /**
     * Release the client's lock on the node.
     */
    private void unlock() throws Exception {
        lock.release();
    }

}
