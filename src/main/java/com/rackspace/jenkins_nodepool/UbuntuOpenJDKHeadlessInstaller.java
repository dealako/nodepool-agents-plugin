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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An installer that performs the necessary steps to install the Open JDK 8 JRE on a remote Ubuntu NodePool slave node.
 */
public class UbuntuOpenJDKHeadlessInstaller extends NodePoolJDKInstaller {

    /**
     * Our class logger.
     */
    private static final Logger LOG = Logger.getLogger(UbuntuOpenJDKHeadlessInstaller.class.getName());

    public static final String OPEN_JDK_8_JRE_PKG = "openjdk-8-jre-headless";
    public static final String JAVA_HOME = "/usr/lib/jvm/java-8-openjdk-amd64";

    /**
     * Increment this when modifying this class.
     */
    public static final long serialVersionUID = 1L;

    /**
     * Creates a new Ubuntu OpenJDK Headless installer.
     */
    public UbuntuOpenJDKHeadlessInstaller() {
        super(OPEN_JDK_8_JRE_PKG);
    }

    /**
     * Returns the Java home folder associated with this installation.
     *
     * @return the Java home folder associated with this installation
     */
    @Override
    public String getJavaHome() {
        return JAVA_HOME;
    }

    /**
     * Ensure that the Java is really installed.
     * If it is already installed, do nothing.
     * Called only if {@link #appliesTo(Node)} are true.
     *
     * @param node the computer on which to install the tool
     * @param tl   any status messages produced by the installation go here
     * @return the (directory) path at which the tool can be found,
     * typically coming from {@link #preferredLocation}
     * @throws IOException          if installation fails
     * @throws InterruptedException if communication with a agent is interrupted
     */
    @Override
    public FilePath performInstallation(Node node, TaskListener tl, Connection connection) throws IOException, InterruptedException {

        if (connection == null) {
            throw new InterruptedException("Connection is null - please set the connection before performing the installation.");
        }

        // Install the Openjdk 8 JRE
        final String[] installCommands = new String[]{
                "apt-get", "update",
                "&&",
                "apt-get", "install", OPEN_JDK_8_JRE_PKG, "-y"
        };
        log(Level.INFO, tl, String.format("Installing %s using command: %s", OPEN_JDK_8_JRE_PKG, Arrays.toString(installCommands)));

        final RemoteLauncher launcher = new RemoteLauncher(tl, connection);
        final int exitCode = executeCommand(tl, launcher, installCommands);

        if (exitCode != 0) {
            log(Level.WARNING, tl, String.format(
                    "Failed to install %s using command: %s via performInstallation() for node: %s - exit code is: %d",
                    OPEN_JDK_8_JRE_PKG, Arrays.toString(installCommands), node, exitCode));
            throw new AbortException(Messages.JDKInstaller_FailedToInstallJDK(exitCode));
        } else {
            log(Level.INFO, tl, String.format("Installed %s", OPEN_JDK_8_JRE_PKG));
        }

        // Let's test to see if the java installation was successful
        if (isJavaInstalled(launcher, tl)) {
            log(Level.INFO, tl, String.format("Running java command was successful for node: %s", node));
        } else {
            log(Level.WARNING, tl, String.format("Running java command was NOT successful for node: %s", node));
        }

        return new FilePath(new File(JAVA_HOME));
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
