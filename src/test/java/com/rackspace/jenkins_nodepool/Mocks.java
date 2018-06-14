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

import com.google.gson.Gson;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

/**
 * This class contains all the fixtures and mocks for unit testing.
 *
 */
public class Mocks {

    Charset charset;
    CuratorFramework conn;
    String connectionString;
    String credentialsID;
    String host;
    String jsonString;
    String key;
    Label label;
    String labelPrefix;
    String nodeRoot;
    NodePool np;
    String npID;
    String npLabel;
    String npcName;
    NodePoolNode npn;
    NodePoolSlave nps;
    NodePoolComputer npc;
    Double port; // I know this is dumb. I think its because GSON creates a double when deserialising.

    String priority;
    Queue.Item queueItem;
    String requestRoot;
    String requestor;
    Task task;
    String value;
    String zkNamespace;
    TestingServer zkTestServer;
    NodeRequest nr;
    List<NodePoolNode> allocatedNodes;
    Integer requestTimeout;
    String jdkInstallationScript;
    String jdkHome;

    public Mocks() {
        requestTimeout = 30;
        requestor = "unittests";
        priority = "001";
        labelPrefix = "nodepool-";
        value = "a map value";
        key = "map key";
        npID = "000000001";
        jsonString = MessageFormat.format("'{'\"{0}\":\"{1}\"'}'", key, value);
        zkNamespace = "unittest";
        nodeRoot = "nodes";
        requestRoot = "requests";
        charset = Charset.forName("UTF-8");
        npLabel = "debian";
        host = "host";
        port = 22.0;
        credentialsID = "somecreds";
        label = new LabelAtom(MessageFormat.format("{0}{1}", labelPrefix, npLabel));
        npcName = MessageFormat.format("{0}-{1}", label.getDisplayName(), npID);
        np = mock(NodePool.class, withSettings().serializable());
        task = mock(Task.class);
        queueItem = mock(Queue.Item.class);
        npn = mock(NodePoolNode.class);
        nps = mock(NodePoolSlave.class);
        nr = mock(NodeRequest.class);
        npc = mock(NodePoolComputer.class);
        jdkInstallationScript = "apt-get update && apt-get install openjdk-8-jre-headless -y";
        jdkHome = "/usr/lib/jvm/java-8-openjdk-amd64";

        startTestServer();
        when(npn.getName()).thenReturn(npcName);
        // final, can't be mocked: when(nps.toComputer()).thenReturn(npc);
        when(nps.getNodePoolNode()).thenReturn(npn);
        when(nps.getNumExecutors()).thenReturn(1);
        when(nps.getLabelString()).thenReturn(label.getDisplayName());
        when(nps.getNodeName()).thenReturn(npcName);
        when(queueItem.getAssignedLabel()).thenReturn(label);
        when(np.getConn()).thenReturn(conn);
        when(np.getRequestor()).thenReturn(requestor);
        when(np.getCharset()).thenReturn(charset);
        when(np.getLabelPrefix()).thenReturn(labelPrefix);
        when(np.getRequestRoot()).thenReturn(requestRoot);
        when(np.getNodeRoot()).thenReturn(nodeRoot);
        when(task.getAssignedLabel()).thenReturn(label);
        when(np.nodePoolLabelFromJenkinsLabel(label.getDisplayName())).thenReturn(npLabel);

        allocatedNodes = new ArrayList();
        allocatedNodes.add(npn);
        try {
            when(nr.getAllocatedNodes()).thenReturn(allocatedNodes);
        } catch (Exception ex) {
            Logger.getLogger(Mocks.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    void cleanup() {
        try {
            zkTestServer.stop();
        } catch (IOException ex) {
            Logger.getLogger(Mocks.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    Map getNodeData(String path) throws Exception {
        byte[] rdata = conn.getData().forPath(path);
        String rString = new String(rdata, charset);
        return new Gson().fromJson(rString, HashMap.class);
    }

    void writeNodeData(String path, Map data) throws Exception {
        byte[] bdata = new Gson().toJson(data).getBytes(charset);
        if (conn.checkExists().forPath(path) != null) {
            conn.setData().forPath(path, bdata);
        } else {
            conn.create()
                    .creatingParentsIfNeeded()
                    .forPath(path, bdata);
        }
    }

    private void startTestServer() {

        try {
            zkTestServer = new TestingServer();
            zkTestServer.start();
            connectionString = zkTestServer.getConnectString();
            conn = CuratorFrameworkFactory.builder()
                    .connectString(connectionString)
                    .namespace(zkNamespace)
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            conn.start();
        } catch (Exception ex) {
            Logger.getLogger(Mocks.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
