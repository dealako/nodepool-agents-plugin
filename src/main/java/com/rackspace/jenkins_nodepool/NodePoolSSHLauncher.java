package com.rackspace.jenkins_nodepool;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.trilead.ssh2.*;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.Messages;
import hudson.plugins.sshslaves.PluginImpl;
import hudson.plugins.sshslaves.SFTPClient;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.HostKey;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.plugins.sshslaves.verifiers.SshHostKeyVerificationStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.NamingThreadFactory;
import hudson.util.NullStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NodePool SSH Launcher class - derived from the SSHLauncher plugin which didn't handle custom JDK installations.
 */
public class NodePoolSSHLauncher extends ComputerLauncher {
    /**
     * Our class logger.
     */
    private static final Logger LOG = Logger.getLogger(NodePoolSSHLauncher.class.getName());

    private final NodePoolJDKInstaller jdkInstaller;

    private final String host;
    private final int port;
    private final String jvmOptions;
    private final String prefixStartSlaveCmd;
    private final String suffixStartSlaveCmd;
    private final int launchTimeoutSeconds;
    private final int maxNumRetries;
    private final int retryWaitTimeSeconds;
    private final SshHostKeyVerificationStrategy sshHostKeyVerificationStrategy;

    private final String credentialsId;

    /**
     * SSH connection to the slave.
     */
    private transient volatile Connection connection;

    /**
     * Constructor SSHLauncher creates a new SSHLauncher instance.
     *
     * @param host                           The host to connect to.
     * @param port                           The port to connect on.
     * @param credentialsId                  The credentials to connect as.
     * @param jvmOptions                     Options passed to the java vm.
     * @param jdkInstaller                   The jdk installer that will be used.
     * @param prefixStartSlaveCmd            This will prefix the start slave command. For instance if you want to execute the command with a different shell.
     * @param suffixStartSlaveCmd            This will suffix the start slave command.
     * @param launchTimeoutSeconds           Launch timeout in seconds
     * @param maxNumRetries                  The number of times to retry connection if the SSH connection is refused during initial connect
     * @param retryWaitTimeSeconds           The number of seconds to wait between retries
     * @param sshHostKeyVerificationStrategy the ssh host key verification strategy
     */
    public NodePoolSSHLauncher(String host, int port, String credentialsId, String jvmOptions,
                               NodePoolJDKInstaller jdkInstaller, String prefixStartSlaveCmd, String suffixStartSlaveCmd,
                               int launchTimeoutSeconds, Integer maxNumRetries, Integer retryWaitTimeSeconds,
                               SshHostKeyVerificationStrategy sshHostKeyVerificationStrategy) {
        this.host = host;
        this.port = port;
        this.credentialsId = credentialsId;
        this.jvmOptions = jvmOptions;
        if (jdkInstaller == null) {
            this.jdkInstaller = new UbuntuOpenJDKHeadlessInstaller();
        } else {
            this.jdkInstaller = jdkInstaller;
        }
        this.prefixStartSlaveCmd = prefixStartSlaveCmd;
        this.suffixStartSlaveCmd = suffixStartSlaveCmd;
        this.launchTimeoutSeconds = launchTimeoutSeconds;
        this.maxNumRetries = maxNumRetries;
        this.retryWaitTimeSeconds = retryWaitTimeSeconds;
        this.sshHostKeyVerificationStrategy = sshHostKeyVerificationStrategy;
    }

    public NodePoolJDKInstaller getToolInstaller() {
        return jdkInstaller;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public String getPrefixStartSlaveCmd() {
        return prefixStartSlaveCmd;
    }

    public String getSuffixStartSlaveCmd() {
        return suffixStartSlaveCmd;
    }

    public int getLaunchTimeoutSeconds() {
        return launchTimeoutSeconds;
    }

    public int getMaxNumRetries() {
        return maxNumRetries;
    }

    public int getRetryWaitTimeSeconds() {
        return retryWaitTimeSeconds;
    }

    public SshHostKeyVerificationStrategy getSshHostKeyVerificationStrategy() {
        return sshHostKeyVerificationStrategy;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public Connection getConnection() {
        return connection;
    }

    /**
     * Launch routine.
     *
     * @param computer the slave computer reference
     * @param tl       the task listener
     * @throws InterruptedException if an error occurs launching
     */
    @Override
    public synchronized void launch(final SlaveComputer computer, final TaskListener tl) throws InterruptedException {
        // Make sure he JDK is installed and launch the agent
        info(tl, String.format("Launching installer thread for computer: %s", computer));
        launchAgent(computer, tl);
    }

    public StandardUsernameCredentials getCredentials() {
        try {
            // only ever want from the system
            // lookup every time so that we always have the latest
            return SSHLauncher.lookupSystemCredentials(credentialsId);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, String.format("%s while looking up credentials with ID: %s", t.getClass().getSimpleName(), credentialsId));
            return null;
        }
    }

    /**
     * Launches the JDK/JRE installer if needed, copies over the Jenkins agent JAR file, and executes the agent.
     *
     * @param computer the slave computer reference
     * @param tl       the task listener
     * @throws InterruptedException if an error occurs while connecting and executing the commands
     */
    public synchronized void launchAgent(final SlaveComputer computer, final TaskListener tl) throws InterruptedException {
        // Create a new connection
        connection = new Connection(getHost(), getPort());

        // We'll use a thread executor to run the tasks
        final ExecutorService launcherExecutorService = Executors.newSingleThreadExecutor(
                new NamingThreadFactory(
                        Executors.defaultThreadFactory(),
                        "NodePoolSSHLauncher.Agent launch for '" + computer.getName() + "' node"));

        info(tl, String.format("Launching JRE installer, agent installer, and execute thread for computer: %s", computer));
        final Set<Callable<Boolean>> callables = new HashSet<Callable<Boolean>>();

        // Add the body of work as a Callable object
        callables.add(new Callable<Boolean>() {
            public Boolean call() throws InterruptedException {

                Boolean rval = Boolean.FALSE;

                try {
                    // Setup the ssh key verification and algorithm details
                    final SshHostKeyVerificationStrategy sshHostKeyVerificationStrategy =
                            getSshHostKeyVerificationStrategy() != null ? getSshHostKeyVerificationStrategy() : new NonVerifyingKeyVerificationStrategy();
                    final String[] preferredKeyAlgorithms = sshHostKeyVerificationStrategy.getPreferredKeyAlgorithms(computer);
                    if (preferredKeyAlgorithms != null && preferredKeyAlgorithms.length > 0) { // JENKINS-44832
                        connection.setServerHostKeyAlgorithms(preferredKeyAlgorithms);
                    } else {
                        warn(tl, "Warning: no key algorithms provided; JENKINS-42959 disabled");
                    }

                    // Open the connection to the slave
                    openConnection(tl, computer);
                    // Clean up the connection
                    verifyNoHeaderJunk(tl);
                    // Show/Dump the environment details
                    reportEnvironment(tl, computer);

                    // Perform the JDK/JRE installation
                    final FilePath jdkInstallationFolder = jdkInstaller.performInstallation(computer.getNode(), tl, connection);
                    info(tl, String.format("Installation is complete for node: %s on %s:%d.  Installation folder is: %s",
                            computer, getHost(), getPort(), jdkInstallationFolder));

                    // The java binary _should_ be in the path now
                    final String java = "java";
                    final String workingDirectory = "/tmp";

                    info(tl, String.format("Copying over the slave jar for node: %s on %s:%d",
                            computer, getHost(), getPort()));
                    copySlaveJar(tl, workingDirectory);

                    info(tl, String.format("Starting Jenkins agent for node: %s on %s:%d",
                            computer, getHost(), getPort()));
                    startSlave(computer, tl, java, workingDirectory);

                    info(tl, String.format("Registering Jenkins agent for node: %s on %s:%d",
                            computer, getHost(), getPort()));
                    PluginImpl.register(connection);

                    rval = Boolean.TRUE;
                } catch (RuntimeException | Error e) {
                    warn(tl, String.format("%s while performing installation for node: %s on %s:%d. Message: %s",
                            e.getClass().getSimpleName(), computer, getHost(), getPort(), e.getLocalizedMessage()));
                    e.printStackTrace(tl.error(Messages.SSHLauncher_UnexpectedError()));
                } catch (AbortException e) {
                    warn(tl, String.format("%s while performing installation for node: %s on %s:%d. Message: %s",
                            e.getClass().getSimpleName(), computer, getHost(), getPort(), e.getLocalizedMessage()));
                    tl.getLogger().println(e.getMessage());
                } catch (IOException e) {
                    warn(tl, String.format("%s while performing installation for node: %s on %s:%d. Message: %s",
                            e.getClass().getSimpleName(), computer, getHost(), getPort(), e.getLocalizedMessage()));
                    e.printStackTrace(tl.getLogger());
                } catch (InterruptedException e) {
                    warn(tl, String.format("%s while performing installation for node: %s on %s:%d. Message: %s",
                            e.getClass().getSimpleName(), computer, getHost(), getPort(), e.getLocalizedMessage()));
                    e.printStackTrace();
                } finally {
                    return rval;
                }
            }
        });

        final Node node = computer.getNode();
        final String nodeName = node != null ? node.getNodeName() : "unknown";
        if (node != null) {
            CredentialsProvider.track(node, getCredentials());
        }
        try {
            long time = System.currentTimeMillis();
            List<Future<Boolean>> results;

            if (this.getLaunchTimeoutMillis() > 0) {
                results = launcherExecutorService.invokeAll(callables, this.getLaunchTimeoutMillis(), TimeUnit.MILLISECONDS);
            } else {
                results = launcherExecutorService.invokeAll(callables);
            }

            long duration = System.currentTimeMillis() - time;
            Boolean res;

            try {
                res = results.get(0).get();
            } catch (ExecutionException e) {
                warn(tl, String.format("%s while running install. Message: %s", e.getClass().getSimpleName(), e.getLocalizedMessage()));
                res = Boolean.FALSE;
            }

            if (res) {
                info(tl, String.format("SSH Launch of node %s on %s:%d completed in %d ms",
                        nodeName, getHost(), getPort(), duration));
            } else {
                warn(tl, String.format("SSH Launch failed for node %s on %s:%d, took %d ms. Cleaning up the connection.",
                        nodeName, getHost(), getPort(), duration));
                cleanupConnection(tl);
            }
        } catch (InterruptedException e) {
            warn(tl, String.format("SSH Launch failed for node %s on %s:%d with a %s error.",
                    nodeName, getHost(), getPort(), e.getClass().getSimpleName()));
        } finally {
            launcherExecutorService.shutdownNow();
        }
    }

    /**
     * Makes sure that SSH connection won't produce any unwanted text, which will interfere with sftp execution.
     *
     * @param tl the task listener
     */
    private void verifyNoHeaderJunk(TaskListener tl) throws IOException, InterruptedException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        connection.exec("true", baos);
        final String s;
        //TODO: Seems we need to retrieve the encoding from the connection destination
        try {
            s = baos.toString(Charset.defaultCharset().name());
        } catch (UnsupportedEncodingException ex) { // Should not happen
            throw new IOException("Default encoding is unsupported", ex);
        }

        if (s.length() != 0) {
            fine(tl, Messages.SSHLauncher_SSHHeaderJunkDetected());
            fine(tl, s);
            throw new AbortException();
        }
    }

    /**
     * Called to terminate the SSH connection. Used liberally when we back out from an error.
     */
    private void cleanupConnection(TaskListener listener) {
        // we might be called multiple times from multiple finally/catch block,
        if (connection != null) {
            connection.close();
            connection = null;
            listener.getLogger().println(Messages.SSHLauncher_ConnectionClosed(getTimestamp()));
        }
    }

    private long getLaunchTimeoutMillis() {
        return TimeUnit.SECONDS.toMillis(launchTimeoutSeconds);
    }

    protected void openConnection(final TaskListener tl, final SlaveComputer computer) throws IOException, InterruptedException {
        info(tl, String.format("Opening SSH connection for node: %s on %s:%d", computer, getHost(), getPort()));
        connection.setTCPNoDelay(true);

        int maxNumRetries = this.maxNumRetries < 0 ? 0 : this.maxNumRetries;
        for (int i = 0; i <= maxNumRetries; i++) {
            try {
                // We pass launch timeout so that the connection will be able to abort once it reaches the timeout
                // It is a poor man's logic, but it should cause termination if the connection goes strongly beyond the timeout
                //TODO: JENKINS-48617 and JENKINS-48618 need to be implemented to make it fully robust
                int launchTimeoutMillis = (int) getLaunchTimeoutMillis();
                connection.connect((hostname, port, serverHostKeyAlgorithm, serverHostKey) -> {

                    final HostKey key = new HostKey(serverHostKeyAlgorithm, serverHostKey);

                    final SshHostKeyVerificationStrategy sshHostKeyVerificationStrategy = getSshHostKeyVerificationStrategy() != null ? getSshHostKeyVerificationStrategy() : new NonVerifyingKeyVerificationStrategy();
                    return sshHostKeyVerificationStrategy.verify(computer, key, tl);
                }, launchTimeoutMillis, 0 /*read timeout - JENKINS-48618*/, launchTimeoutMillis);
                break;
            } catch (IOException ioexception) {
                @CheckForNull String message = "";
                Throwable cause = ioexception.getCause();
                if (cause != null) {
                    message = cause.getMessage();
                    warn(tl, message);
                }
                if (cause == null) {
                    throw ioexception;
                }
                if (maxNumRetries - i > 0) {
                    info(tl, String.format("SSH Connection failed with IOException - message is: %s, retrying in %d seconds.  There are %d more retries left.",
                            message, this.retryWaitTimeSeconds, (maxNumRetries - i)));
                } else {
                    warn(tl, String.format("SSH Connection failed with IOException - message is: %s", message));
                    throw ioexception;
                }
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(retryWaitTimeSeconds));
        }

        StandardUsernameCredentials credentials = getCredentials();
        if (credentials == null) {
            final String msg = "Cannot find SSH User credentials with id: " + credentialsId;
            warn(tl, msg);
            throw new AbortException(msg);
        }
        if (SSHAuthenticator.newInstance(connection, credentials).authenticate(tl)
                && connection.isAuthenticationComplete()) {
            info(tl, String.format("SSH authentication successful for node: %s on %s:%d",
                    computer, getHost(), getPort()));
        } else {
            warn(tl, String.format("SSH authentication failed for node: %s on: %s:%d",
                    computer, getHost(), getPort()));
            throw new AbortException(Messages.SSHLauncher_AuthenticationFailedException());
        }
    }

    protected void reportEnvironment(TaskListener tl, final SlaveComputer computer) throws IOException, InterruptedException {
        fine(tl, String.format("Dumping environment for node: %s on: %s:%d", computer, getHost(), getPort()));
        connection.exec("set", tl.getLogger());
    }

    /**
     * Method copies the slave jar to the remote system.
     *
     * @param tl               the task listener
     * @param workingDirectory The directory into which the slave jar will be copied.
     * @throws IOException If something goes wrong.
     */
    private void copySlaveJar(TaskListener tl, String workingDirectory) throws IOException, InterruptedException {
        String fileName = workingDirectory + "/slave.jar";

        info(tl, String.format("Starting sftp client to: %s:%d", getHost(), getPort()));
        SFTPClient sftpClient = null;
        try {
            sftpClient = new SFTPClient(connection);

            try {
                final SFTPv3FileAttributes fileAttributes = sftpClient._stat(workingDirectory);
                if (fileAttributes == null) {
                    info(tl, Messages.SSHLauncher_RemoteFSDoesNotExist(getTimestamp(), workingDirectory));
                    sftpClient.mkdirs(workingDirectory, 0700);
                } else if (fileAttributes.isRegularFile()) {
                    throw new IOException(Messages.SSHLauncher_RemoteFSIsAFile(workingDirectory));
                }

                try {
                    // try to delete the file in case the slave we are copying is shorter than the slave
                    // that is already there
                    sftpClient.rm(fileName);
                } catch (IOException e) {
                    // the file did not exist... so no need to delete it!
                }

                try {
                    info(tl, "Copying latest slave.jar...");
                    byte[] slaveJar = new Slave.JnlpJar("slave.jar").readFully();
                    // Transfer the JAR over - use resource management to auto-close/cleanup
                    try (OutputStream os = sftpClient.writeToFile(fileName)) {
                        os.write(slaveJar);
                    }
                    info(tl, String.format("Copied %d bytes", slaveJar.length));
                } catch (Error error) {
                    warn(tl, String.format("%s wile copying over slave.jar via SFTP. Message: %s",
                            error.getClass().getSimpleName(), error.getLocalizedMessage()));
                    throw error;
                } catch (Throwable e) {
                    warn(tl, String.format("%s wile copying over slave.jar via SFTP. Message: %s",
                            e.getClass().getSimpleName(), e.getLocalizedMessage()));
                    throw new IOException(Messages.SSHLauncher_ErrorCopyingSlaveJarTo(fileName), e);
                }
            } catch (Error error) {
                warn(tl, String.format("%s wile copying over slave.jar via SFTP. Message: %s",
                        error.getClass().getSimpleName(), error.getLocalizedMessage()));
                throw error;
            } catch (Throwable e) {
                warn(tl, String.format("%s wile copying over slave.jar via SFTP. Message: %s",
                        e.getClass().getSimpleName(), e.getLocalizedMessage()));
                throw new IOException(Messages.SSHLauncher_ErrorCopyingSlaveJarInto(workingDirectory), e);
            }
        } catch (IOException e) {
            if (sftpClient == null) {
                e.printStackTrace(tl.error(Messages.SSHLauncher_StartingSCPClient(getTimestamp())));
                // lets try to recover if the slave doesn't have an SFTP service
                copySlaveJarUsingSCP(tl, workingDirectory);
            } else {
                throw e;
            }
        } finally {
            if (sftpClient != null) {
                sftpClient.close();
            }
        }
    }

    /**
     * Method copies the slave jar to the remote system using scp.
     *
     * @param tl               The listener.
     * @param workingDirectory The directory into which the slave jar will be copied.
     * @throws IOException          If something goes wrong
     * @throws InterruptedException If something goes wrong
     */
    private void copySlaveJarUsingSCP(TaskListener tl, String workingDirectory) throws IOException, InterruptedException {
        SCPClient scp = new SCPClient(connection);
        try {
            // check if the working directory exists
            if (connection.exec("test -d " + workingDirectory, tl.getLogger()) != 0) {
                fine(tl, Messages.SSHLauncher_RemoteFSDoesNotExist(getTimestamp(), workingDirectory));
                // working directory doesn't exist, lets make it.
                if (connection.exec("mkdir -p " + workingDirectory, tl.getLogger()) != 0) {
                    warn(tl, "Failed to create " + workingDirectory);
                }
            }

            // delete the slave jar as we do with SFTP
            connection.exec("rm " + workingDirectory + "/slave.jar", new NullStream());

            // SCP it to the slave. hudson.Util.ByteArrayOutputStream2 doesn't work for this. It pads the byte array.
            info(tl, Messages.SSHLauncher_CopyingSlaveJar(getTimestamp()));
            scp.put(new Slave.JnlpJar("slave.jar").readFully(), "slave.jar", workingDirectory, "0644");
        } catch (IOException e) {
            throw new IOException(Messages.SSHLauncher_ErrorCopyingSlaveJarInto(workingDirectory), e);
        }
    }

    /**
     * Starts the slave process.
     *
     * @param computer         the slave computer reference
     * @param tl               the task listener
     * @param java             The full path name of the java executable to use
     * @param workingDirectory The working directory from which to start the java process
     * @throws IOException if something goes wrong while starting the slave agent
     */
    private void startSlave(SlaveComputer computer, final TaskListener tl, String java, String workingDirectory) throws IOException {
        final Session session = connection.openSession();
        expandChannelBufferSize(session, tl);
        String cmd = "cd \"" + workingDirectory + "\" && " + java + " " + getJvmOptions() + " -jar slave.jar";

        //This will wrap the cmd with prefix commands and suffix commands if they are set.
        cmd = getPrefixStartSlaveCmd() + cmd + getSuffixStartSlaveCmd();

        info(tl, String.format("Starting slave with command: %s", cmd));
        session.execCommand(cmd);

        session.pipeStderr(new DelegateNoCloseOutputStream(tl.getLogger()));

        try {
            computer.setChannel(session.getStdout(), session.getStdin(), tl.getLogger(), null);
        } catch (InterruptedException e) {
            session.close();
            throw new IOException(Messages.SSHLauncher_AbortedDuringConnectionOpen(), e);
        } catch (IOException e) {
            try {
                // often times error this early means the JVM has died, so let's see if we can capture all stderr
                // and exit code
                throw new AbortException(getSessionOutcomeMessage(session, false));
            } catch (InterruptedException x) {
                throw new IOException(e);
            }
        }
    }

    private void expandChannelBufferSize(Session session, TaskListener tl) {
        // see hudson.remoting.Channel.PIPE_WINDOW_SIZE for the discussion of why 1MB is in the right ball park
        // but this particular session is where all the master/slave communication will happen, so
        // it's worth using a bigger buffer to really better utilize bandwidth even when the latency is even larger
        // (and since we are draining this pipe very rapidly, it's unlikely that we'll actually accumulate this much data)
        int sz = 4;
        session.setWindowSize(sz * 1024 * 1024);
        fine(tl, "Expanded the channel window size to " + sz + "MB");
    }

    /**
     * Find the exit code or exit status, which are differentiated in SSH protocol.
     */
    private String getSessionOutcomeMessage(Session session, boolean isConnectionLost) throws InterruptedException {
        session.waitForCondition(ChannelCondition.EXIT_STATUS | ChannelCondition.EXIT_SIGNAL, 3000);

        Integer exitCode = session.getExitStatus();
        if (exitCode != null)
            return "Slave JVM has terminated. Exit code=" + exitCode;

        String sig = session.getExitSignal();
        if (sig != null)
            return "Slave JVM has terminated. Exit signal=" + sig;

        if (isConnectionLost)
            return "Slave JVM has not reported exit code before the socket was lost";

        return "Slave JVM has not reported exit code. Is it still running?";
    }

    /**
     * Returns the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    private static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    /**
     * Logs the specified message to both the logger and the task listener.
     *
     * @param lvl      the log level
     * @param listener the task listener
     * @param msg      the message to log
     */
    private static void log(Level lvl, TaskListener listener, String msg) {
        listener.getLogger().println(String.format("%s [%s] %s", getTimestamp(), lvl, msg));
        LOG.log(lvl, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    private static void fine(TaskListener listener, String msg) {
        log(Level.FINE, listener, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    private static void info(TaskListener listener, String msg) {
        log(Level.INFO, listener, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    private static void warn(TaskListener listener, String msg) {
        log(Level.WARNING, listener, msg);
    }
}
