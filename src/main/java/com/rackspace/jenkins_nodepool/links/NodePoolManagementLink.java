package com.rackspace.jenkins_nodepool.links;

import com.rackspace.jenkins_nodepool.NodePool;
import com.rackspace.jenkins_nodepool.NodePoolJobHistory;
import com.rackspace.jenkins_nodepool.NodePools;
import hudson.Extension;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;

/**
 * Adds a link under "Manage Jenkins" which can be accessed to display information related to the health and
 * functioning of the Jenkins+NodePool environment.
 */
@Extension
public class NodePoolManagementLink extends hudson.model.ManagementLink {

    @CheckForNull
    @Override
    public String getIconFileName() {
        return "network.png";
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return "NodePool";
    }

    @Override
    public String getDescription() {
        return "View health and diagnostic information related to the NodePool plugin.";
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return "nodepool-view";
    }

    public List<NodePool> getNodePools() {
        final NodePools nodePools = NodePools.get();
        return nodePools.getNodePools();
    }

    public NodePoolJobHistory getJobHistory() {
        final NodePools nodePools = NodePools.get();
        return nodePools.getJobHistory();
    }

    /**
     * Triggers/Performs the page update now.
     *
     * @param req the stapler request object
     * @param rsp the stapler response object
     * @throws IOException if an error occurs dispatching the request
     * @throws ServletException if an error occurs dispatching the request
     */
    @RequirePOST
    public void doUpdateNow(StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        rsp.forwardToPreviousPage(req);
    }
}
