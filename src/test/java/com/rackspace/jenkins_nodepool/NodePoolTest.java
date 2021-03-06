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

import hudson.model.Descriptor;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * A test class for NodePool.
 *
 * @author hughsaunders
 */
public class NodePoolTest {

    /**
     * Logger for this class.
     */
    private static final Logger LOG = getLogger(NodePoolTest.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private Mocks m;
    private NodePool np;

    public NodePoolTest() {
    }

    @BeforeClass
    public static void setUpClass() {
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() {
        m = new Mocks();
        np = new NodePool(
                m.connectionString,
                m.credentialsID,
                m.labelPrefix,
                m.requestRoot,
                m.priority,
                m.requestor,
                m.zkNamespace,
                m.nodeRoot,
                m.requestTimeout,
                m.jdkInstallationScript,
                m.jdkHome,
                m.installTimeout,
                m.maxAttempts
        );
    }

    @After
    public void tearDown() {
    }

    /**
     * Test of createZKConnection method, of class NodePool.
     */
    @Test
    public void testCreateZKConnection() {
        CuratorFramework c = NodePool.createZKConnection(m.connectionString, m.zkNamespace);
        assertEquals(CuratorFrameworkState.STARTED, c.getState());
        c.close();
    }

    /**
     * Test of acceptNodes method, of class NodePool.
     */
    @Test
    public void testAcceptNodes() throws Exception {
        np.requests.add(m.nr);
        assertTrue(m.nr.getAllocatedNodes().size() == 1);
        np.acceptNodes(m.nr);
        verify(m.nr).delete();
        verify(m.npn).setInUse();
    }


    @Test
    public void debugTest() throws Exception {
        assertTrue(m.nr.getAllocatedNodes().size() == 1);
    }

    /**
     * Test of getDescriptor method, of class NodePool.
     */
    @Test
    public void testGetDescriptor() {
        assertTrue(np.getDescriptor() instanceof Descriptor);
    }

    /**
     * Test of nodePoolLabelFromJenkinsLabel method, of class NodePool.
     */
    @Test
    public void testNodePoolLabelFromJenkinsLabel() {
        assertEquals(m.npLabel, np.nodePoolLabelFromJenkinsLabel(m.label.getDisplayName()));
    }

    /**
     * Test of setConnectionString method, of class NodePool.
     */
    @Test
    public void testSetConnectionString() {
        CuratorFramework c = np.getConn();
        String connectionString = np.getConnectionString();
        np.setConnectionString(connectionString);
        assertSame(c, np.getConn());
    }

    /**
     * Test of idForPath method, of class NodePool.
     */
    @Test
    public void testIdForPath() throws Exception {
        String path = format("/foo/bah/wib-%s", m.npID);
        assertEquals(m.npID, np.idForPath(path));
    }

    /**
     * Test of provisionNode method, of class NodePool. This method checks that
     * The provision process correctly responds to a node request being
     * fulfilled. Its not exhaustive as no nodes are actually created.
     */
    //@Test TODO: need to fix this test
    public void testProvisionNode() throws Exception {
        new Thread(() -> {
            try {

                // This thread pretends to be nodepool, it will set the
                // state of the pending request to fulfilled.
                m.conn.create().creatingParentsIfNeeded().forPath("/" + m.requestRoot);

                // find request znode
                String child = null;
                int attempts = 10;
                while (attempts-- > 0) {
                    LOG.log(INFO, format("Querying namespace: %s for path: %s", m.conn.getNamespace(), m.requestRoot));
                    final List<String> children = m.conn.getChildren().forPath("/" + m.requestRoot);
                    if (children.isEmpty()) {
                        LOG.log(INFO, format("No children discovered for path: %s", m.requestRoot));
                        Thread.sleep(500);
                    } else {
                        LOG.log(INFO, format("%d children discovered for path: %s", children.size(), m.requestRoot));
                        child = children.get(0);
                        break;
                    }
                }
                assertNotNull(child);
            } catch (Exception ex) {
                fail(format("%s while testing provision node. Message: %s",
                        ex.getClass().getSimpleName(), ex.getLocalizedMessage()));
            }
        }).start();

        final NodePoolJob job = new NodePoolJob(m.label, m.task, m.qID);
        job.setNodePoolNode(m.npn);
        job.setNodePoolSlave(m.nps);
        np.provisionNode(job, m.requestTimeout, m.maxAttempts, m.installTimeout);

        // this test will timeout on failure
    }

    @Test
    public void testProvisionNodeTimeout() throws Exception {
        // this test should timeout, because there is no nodepool instance
        // to fulfil requests.
        Long start = System.currentTimeMillis();
        int requestTimeout = 2;
        final NodeRequest request = new NodeRequest(m.np, m.priority, m.npj);

        try {
            np.attemptProvisionNode2(request, requestTimeout, m.installTimeout);
            fail("Exception Expected, but not thrown");
        } catch (InterruptedException ex) {
            Long end = System.currentTimeMillis();
            long elapsed = ((end - start)) / 1000;
            if (elapsed > requestTimeout + 3 || elapsed < requestTimeout) {
                fail(format("Timeout set to %d, but took %d seconds to fail", requestTimeout, elapsed));
            }
        }
    }

    /**
     * Simulate the first node request failing and the second one succeeding.
     */
    @Test
    public void testRetries() throws Exception {
        final NodePool np = spy(new NodePool(
                null,
                "credentialsId",
                "nodepool-",
                "requests",
                "priority",
                "requestor",
                "nodepool",
                "nodes",
                m.requestTimeout,
                m.jdkInstallationScript,
                m.jdkHome,
                m.installTimeout,
                2
        ));

        final NodePoolJob job = new NodePoolJob(m.label, m.task, m.qID);

        doReturn(m.nr).when(np).createNodeRequest(job);
        //doNothing().when(np).attemptProvision(job, timeoutInSec);
        //when(np.createNodeRequest(job)).thenReturn(m.nr);
        doThrow(new NodePoolException("request fail"))
                .doNothing()
                .when(np)
                .attemptProvision(job, m.requestTimeout, m.installTimeout);

        //np.attemptProvision(job, timeoutInSec);
        np.provisionNode(job);  // lack of exception indicates provisioning success

        verify(np, times(2)).attemptProvision(job, m.requestTimeout, m.installTimeout);
    }

    /**
     * Test the tracking of attempts for a job (success case)
     */
    @Test
    public void testJobTrackingAttemptSuccessful() throws Exception {

        final NodePool np = spy(new NodePool(
                null,
                "credentialsId",
                "nodepool-",
                "requests",
                "priority",
                "requestor",
                "nodepool",
                "nodes",
                m.requestTimeout,
                m.jdkInstallationScript,
                m.jdkHome,
                m.installTimeout,
                m.maxAttempts
        ));


        final NodeRequest request = mock(NodeRequest.class);
        doReturn(request).when(np).createNodeRequest(m.npj);

        doNothing().when(np).attemptProvisionNode2(request, m.requestTimeout, m.installTimeout);

        np.attemptProvision(m.npj, m.requestTimeout, m.installTimeout);

        final List<Attempt> attempts = m.npj.getAttempts();
        assertEquals(1, attempts.size());

        final Attempt attempt = attempts.get(0);
        assertTrue(attempt.isSuccess());

        assertTrue(attempt.getDurationSeconds() >= 0);
    }

    /**
     * Test the tracking of attempts for a job (error case)
     */
    @Test
    public void testJobTrackingAttemptFailure() throws Exception {

        final NodePool np = spy(new NodePool(
                null,
                "credentialsId",
                "nodepool-",
                "requests",
                "priority",
                "requestor",
                "nodepool",
                "nodes",
                m.requestTimeout,
                m.jdkInstallationScript,
                m.jdkHome,
                m.installTimeout,
                m.maxAttempts
        ));


        final NodeRequest request = mock(NodeRequest.class);
        doReturn(request).when(np).createNodeRequest(m.npj);

        doThrow(new NodePoolException("request error")).when(np).attemptProvisionNode2(request, m.requestTimeout, m.installTimeout);

        // override mock default which is to return a single successful attempt
        when(m.npj.getAttempts()).thenReturn(m.attemptListFailure);
        boolean success = true;

        try {
            np.attemptProvision(m.npj, m.requestTimeout, m.installTimeout);
        } catch (NodePoolException e) {
            // expected
            success = false;
        }

        if (success) {
            fail("attemptProvision should have failed.");
        }

        final List<Attempt> attempts = m.npj.getAttempts();
        assertEquals(1, attempts.size());

        final Attempt attempt = attempts.get(0);
        assertTrue(!attempt.isSuccess());
    }

    @Test
    public void testSetRequestTimeout() {
        np.setRequestTimeout(2);
        assertEquals(2, (int) np.getRequestTimeout());

        try {
            np.setRequestTimeout(0);
            fail("Expected exception");
        } catch (IllegalArgumentException ex) {
            //pass
        }

        try {
            np.setRequestTimeout(-1);
            fail("Expected exception");
        } catch (IllegalArgumentException ex) {
            //pass
        }
    }
}
