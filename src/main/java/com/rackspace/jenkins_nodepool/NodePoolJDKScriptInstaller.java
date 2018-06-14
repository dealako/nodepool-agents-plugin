package com.rackspace.jenkins_nodepool;

import com.trilead.ssh2.Connection;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.Messages;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * An installer that accepts a script to perform the necessary steps to install the JDK/JRE on a remote NodePool slave
 * node.
 */
public class NodePoolJDKScriptInstaller extends NodePoolJDKInstaller {

    /**
     * Our class logger.
     */
    private static final Logger LOG = Logger.getLogger(NodePoolJDKScriptInstaller.class.getName());

    public static final String INSTALLER_NAME = "nodepool-jdk-script-installer";

    /**
     * Increment this when modifying this class.
     */
    public static final long serialVersionUID = 1L;

    /**
     * The JDK Installation script (e.g. apt-get update && apt-get install openjdk-8-jre-headless -y)
     */
    private final String jdkInstallationScript;

    /**
     * The JDK home directory for the JDK installation accomplished by the script.
     */
    private final String jdkHome;

    /**
     * Creates a new Ubuntu OpenJDK Headless installer.
     *
     * @param jdkInstallationScript the installation script value
     * @param jdkHome               the java JRE/JDK home
     */
    public NodePoolJDKScriptInstaller(final String jdkInstallationScript, final String jdkHome) {
        super(INSTALLER_NAME);
        this.jdkInstallationScript = jdkInstallationScript;
        this.jdkHome = jdkHome;
    }

    /**
     * Returns the JDK installation script for this installer.
     *
     * @return the JDK installation script for this installer.
     */
    public String getJdkInstallationScript() {
        return jdkInstallationScript;
    }

    /**
     * Returns the Java home folder.
     *
     * @return the Java home folder
     */
    @Override
    public String getJavaHome() {
        return this.jdkHome;
    }

    /**
     * Ensure that the Java is really installed.
     * If it is already installed, do nothing.
     * Called only if {@link #appliesTo(Node)} are true.
     *
     * @param node the computer on which to install the tool
     * @param tl   any status messages produced by the installation go here
     * @return the (directory) path at which the tool can be found, typically coming from {@link #preferredLocation}
     * @throws IOException          if installation fails
     * @throws InterruptedException if communication with a agent is interrupted
     */
    @Override
    public FilePath performInstallation(Node node, TaskListener tl, Connection connection) throws IOException, InterruptedException {

        if (connection == null) {
            throw new InterruptedException("Connection is null - please set the connection before performing the installation.");
        }

        final RemoteLauncher launcher = new RemoteLauncher(tl, connection);

        // Do we need to install? If exists, we'll skip.  This doesn't check for Java version level compatibility.
        if (isJavaInstalled(launcher, tl)) {
            info(tl, String.format("Java appears to be installed. Skipping installation. Note: Since this is an existing installation, JAVA_HOME may not be here: %s", getJavaHome()));
        } else {

            // Install the Openjdk 8 JRE
            final String[] installCommands = new String[]{jdkInstallationScript};
            info(tl, String.format("Installing JDK/JRE using command: %s", Arrays.toString(installCommands)));

            final int exitCode = executeCommand(tl, launcher, installCommands);

            if (exitCode != 0) {
                warn(tl, String.format(
                        "Failed to install JDK/JRE using command: %s via performInstallation() for node: %s - exit code is: %d",
                        Arrays.toString(installCommands), node, exitCode));
                throw new AbortException(Messages.JDKInstaller_FailedToInstallJDK(exitCode));
            } else {
                info(tl, "Installed JDK/JRE");
            }

            // Let's test to see if the java installation was successful
            if (isJavaInstalled(launcher, tl)) {
                info(tl, String.format("Running java command was successful for node: %s", node));
            } else {
                warn(tl, String.format("Running java command was NOT successful for node: %s", node));
            }
        }

        return new FilePath(new File(getJavaHome()));
    }

    /**
     * A simple wrapper method for launching a set of commands.
     *
     * @param log      the task listener
     * @param launcher the launcher
     * @param commands one or more commands (as a vararg)
     * @return the exit code from the command(s)
     * @throws IOException          if an error occurs while launching and running the command
     * @throws InterruptedException if an error occurs while launching and running the command
     */
    private int executeCommand(TaskListener log, Launcher launcher, String... commands) throws IOException, InterruptedException {
        return launcher.launch()
                .cmds(commands)
                .stdout(log)
                .pwd(DEFAULT_INSTALL_WORKING_DIR)
                .join();
    }
}
