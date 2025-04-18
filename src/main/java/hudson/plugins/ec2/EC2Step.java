/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
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
package hudson.plugins.ec2;

import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.services.ec2.model.Instance;

/**
 * Returns the instance provisioned.
 * <p>
 * Used like:
 *
 * <pre>
 * node {
 *     def x = ec2 cloud: 'myCloud', template: 'aws-CentOS-7'
 * }
 * </pre>
 *
 * @author Alicia Doblas
 */
public class EC2Step extends Step {

    private String cloud;
    private String template;

    @DataBoundConstructor
    public EC2Step(String cloud, String template) {
        this.cloud = cloud;
        this.template = template;
    }

    public String getCloud() {
        return cloud;
    }

    public String getTemplate() {
        return template;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new EC2Step.Execution(this, context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "ec2";
        }

        @Override
        public String getDisplayName() {
            return "Cloud template provisioning";
        }

        @POST
        public ListBoxModel doFillCloudItems() {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
            ListBoxModel r = new ListBoxModel();
            r.add("", "");
            Jenkins.get().clouds.getAll(EC2Cloud.class).forEach(c -> r.add(c.getDisplayName(), c.getDisplayName()));
            return r;
        }

        @POST
        public ListBoxModel doFillTemplateItems(@QueryParameter String cloudName) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
            ListBoxModel r = new ListBoxModel();
            Cloud cloud = Jenkins.get().getCloud(Util.fixEmpty(cloudName));
            if (cloud instanceof EC2Cloud ec2Cloud) {
                for (SlaveTemplate template : ec2Cloud.getTemplates()) {
                    for (String labelList : template.labels.split(" ")) {
                        r.add(
                                labelList + "  (AMI: " + template.getAmi() + ", REGION: " + ec2Cloud.getRegion()
                                        + ", TYPE: " + template.type + ")",
                                labelList);
                    }
                }
            }
            return r;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Instance> {
        private final String cloud;
        private final String template;

        Execution(EC2Step step, StepContext context) {
            super(context);
            this.cloud = step.cloud;
            this.template = step.template;
        }

        @Override
        protected Instance run() throws Exception {
            Cloud cl = getByDisplayName(jenkins.model.Jenkins.get().clouds, this.cloud);
            if (cl instanceof EC2Cloud) {
                SlaveTemplate t;
                t = ((EC2Cloud) cl).getTemplate(this.template);
                if (t != null) {
                    SlaveTemplate.ProvisionOptions universe = SlaveTemplate.ProvisionOptions.ALLOW_CREATE;
                    EnumSet<SlaveTemplate.ProvisionOptions> opt = EnumSet.noneOf(SlaveTemplate.ProvisionOptions.class);
                    opt.add(universe);

                    List<EC2AbstractSlave> instances = t.provision(1, opt);
                    if (instances == null) {
                        throw new IllegalArgumentException(
                                "Error in AWS Cloud. Please review AWS template defined in Jenkins configuration.");
                    }

                    EC2AbstractSlave slave = instances.get(0);
                    return CloudHelper.getInstanceWithRetry(slave.getInstanceId(), (EC2Cloud) cl);
                } else {
                    throw new IllegalArgumentException(
                            "Error in AWS Cloud. Please review AWS template defined in Jenkins configuration.");
                }
            } else {
                throw new IllegalArgumentException(
                        "Error in AWS Cloud. Please review EC2 settings in Jenkins configuration.");
            }
        }

        public Cloud getByDisplayName(Jenkins.CloudList clouds, String name) {
            Iterator<Cloud> i$ = clouds.iterator();
            Cloud c;
            c = i$.next();

            while (!c.getDisplayName().equals(name)) {
                if (!i$.hasNext()) {
                    return null;
                }
                c = i$.next();
            }
            return c;
        }
    }
}
