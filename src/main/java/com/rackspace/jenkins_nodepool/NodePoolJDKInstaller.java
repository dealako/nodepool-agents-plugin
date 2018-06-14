package com.rackspace.jenkins_nodepool;

import com.trilead.ssh2.Connection;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class NodePoolJDKInstaller extends ToolInstaller {

    /**
     * The default working directory for installing
     */
    public static final String DEFAULT_INSTALL_WORKING_DIR = "/tmp";

    /**
     * Our class logger.
     */
    private static final Logger LOG = Logger.getLogger(NodePoolJDKInstaller.class.getName());

    /**
     * Creates a new NodePool JDK installer.
     *
     * @param label the label associated with this installer
     */
    public NodePoolJDKInstaller(String label) {
        super(label);
    }

    /**
     * Returns the Java home folder associated with this installation.
     *
     * @return the Java home folder associated with this installation
     */
    public abstract String getJavaHome();

    /**
     * Ensure that the configured tool is really installed. If it is already installed, do nothing.
     *
     * @param tool the tool being installed
     * @param node the computer on which to install the tool
     * @param tl   any status messages produced by the installation go here
     * @return the (directory) path at which the tool can be found,
     * typically coming from {@link #preferredLocation}
     * @throws IOException          if installation fails
     * @throws InterruptedException if communication with a agent is interrupted
     */
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener tl) throws IOException, InterruptedException {
        return performInstallation(node, tl, null);
    }

    /**
     * Ensure that the configured tool is really installed. If it is already installed, do nothing.
     *
     * @param node       the computer on which to install the tool
     * @param tl         any status messages produced by the installation go here
     * @param connection the connection object
     * @return the (directory) path at which the tool can be found,
     * typically coming from {@link #preferredLocation}
     * @throws IOException          if installation fails
     * @throws InterruptedException if communication with a agent is interrupted
     */
    public abstract FilePath performInstallation(Node node, TaskListener tl, Connection connection) throws IOException, InterruptedException;

    /**
     * Routine to run a remote command to determine if java is installed (e.g. java -version).
     *
     * @param launcher the launcher
     * @param tl       the task listener
     * @return true if the command was successful, false otherwise
     * @throws IOException          if an error occurs while launching and running the java version command
     * @throws InterruptedException if an error occurs while launching and running the java version command
     */
    public boolean isJavaInstalled(Launcher launcher, TaskListener tl) throws IOException, InterruptedException {
        info(tl, "Testing to see if java is installed...");
        final int exitCode = launcher.launch().cmds("java", "-version").stdout(tl).pwd("/").join();

        if (exitCode != 0) {
            info(tl, String.format("Failed to execute: java -version, exit code is: %d", exitCode));
        }

        return exitCode == 0;
    }

    /**
     * Returns the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    protected static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    /**
     * Logs the specified message to both the logger and the task listener.
     *
     * @param lvl      the log level
     * @param listener the task listener
     * @param msg      the message to log
     */
    protected static void log(Level lvl, TaskListener listener, String msg) {
        listener.getLogger().println(String.format("%s [%s] %s", getTimestamp(), lvl, msg));
        LOG.log(lvl, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    protected static void fine(TaskListener listener, String msg) {
        log(Level.FINE, listener, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    protected static void info(TaskListener listener, String msg) {
        log(Level.INFO, listener, msg);
    }

    /**
     * Logs the specified message as Level.FINE to both the logger and the task listener.
     *
     * @param listener the task listener
     * @param msg      the message to log
     */
    protected static void warn(TaskListener listener, String msg) {
        log(Level.WARNING, listener, msg);
    }
}
