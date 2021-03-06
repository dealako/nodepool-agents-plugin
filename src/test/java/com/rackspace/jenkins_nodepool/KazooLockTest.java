/*
 * The MIT License
 *
 * Copyright 2018 Rackspace.
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author Rackspace
 */
public class KazooLockTest {

    private static final Logger LOG = Logger.getLogger(KazooLockTest.class.getName());

    List<String> children;
    String path = "/testkazoolock/locknode1";
    Mocks m;

    public KazooLockTest() {
    }

    @BeforeClass
    public static void setUpClass() {
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() throws Exception {
        m = new Mocks();
    }

    @After
    public void tearDown() {
        m.cleanup();
    }

    @Test
    public void testSequenceNumberForPath() throws KazooLockException {
        Integer seq = 99;
        String uuid = UUID.randomUUID().toString();
        String testPath = "/nodepool/node/lock/" + uuid + "__lock__" + seq.toString();
        assertEquals(seq, KazooLock.sequenceNumberForPath(testPath));
    }

    @Test
    public void testAcquireRelease() throws Exception {

        Stat stat = m.conn.checkExists().forPath(path);
        if (stat == null) {
            // create node to ensure acquire doesn't fail if node already exists
            m.conn.create()
                    .creatingParentsIfNeeded()
                    .forPath(path);
        }

        // check newly created node has no children
        children = m.conn.getChildren().forPath(path);
        assertEquals(0, children.size());

        // create and acquire lock
        KazooLock kl = new KazooLock(path, m.np, m.npj);
        assertEquals(KazooLock.State.UNLOCKED, kl.getState());
        kl.acquire();
        assertEquals(KazooLock.State.LOCKED, kl.getState());

        // ensure a child node now exists
        children = m.conn.getChildren().forPath(path);
        assertEquals(1, children.size());
        String lockNode = children.get(0);
        assertTrue(lockNode.contains("__lock__"));

        // release lock and ensure child has been removed
        kl.release();
        assertEquals(KazooLock.State.UNLOCKED, kl.getState());
        children = m.conn.getChildren().forPath(path);
        assertEquals(0, children.size());
    }

    @Test(expected = KazooLockException.class)
    public void testBlockingAcquireTimesOut() throws Exception {
        // create and acquire lock
        final KazooLock kl = new KazooLock(path, 1, TimeUnit.SECONDS, m.np, m.npj);
        kl.acquire();

        // lock is not reentrant, so second acquire should timeout
        kl.acquire();
    }

    // Utility thread used in testAcquireOnRelease
    private class LockHolder extends Thread {

        KazooLock kl;

        public LockHolder(KazooLock kl) {
            this.kl = kl;
        }

        public void run() {
            try {
                Thread.sleep(3000); // three seconds in miliseconds
                kl.release();
            } catch (Exception ex) {
                LOG.severe(ex.getMessage());
            }
        }
    }

    @Test
    public void testAcquireOnRelease() throws Exception {
        // The idea of this test is to ensure that a waiting lock will acquire
        // when contenders that are ahead in the queue remove their nodes.

        // This test also ensures that locks aren't held concurrently by
        // asserting that the amount of time the background thread holds the
        // lock for passes, before our thread's acquire returns.
        Double minimumElapsed = 3E9; // 3 seconds in nanoseconds
        ZkWatcher w = new ZkWatcher();

        // Create two contenders for a single lock. One for this thread and one
        // for the background thread.
        KazooLock kl = new KazooLock(path, m.np, m.npj);
        KazooLock klBackground = new KazooLock(path, m.np, m.npj);

        LockHolder background = new LockHolder(klBackground);
        klBackground.acquire(); // Acquire lock before starting background
        // thread to ensure that this lock is acquired
        // first
        Long postBackgroundAcquire = System.nanoTime();
        background.start(); // non-blocking thread start

        // Attempt to acquire the lock, should block until the background
        // thread releases
        kl.acquire(); // should acquire as timeout is 5 seconds
        // and the background thread only holds for 3.

        // Ensure minimum time has elapsed and release lock;
        Long postAcquire = System.nanoTime();
        kl.release();

        Long elapsed = postAcquire - postBackgroundAcquire;
        assertTrue(elapsed > minimumElapsed);
        LOG.info("elapsed:" + elapsed.toString());
    }
}
