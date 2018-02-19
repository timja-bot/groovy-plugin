package hudson.plugins.groovy;

import com.google.common.collect.ImmutableSet;
import groovy.lang.GroovyObject;
import groovy.lang.Writable;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.ClassFilter;
import hudson.remoting.ObjectInputStreamEx;
import hudson.remoting.VirtualChannel;
import hudson.remoting.Which;
import hudson.slaves.WorkspaceList;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Makes {@code groovy} in {@code PATH} be bound to an appropriate executable.
 */
public class WithGroovyStep extends Step {

    private String tool;
    private String jdk;
    private Object input;

    @DataBoundConstructor
    public WithGroovyStep() {}

    public String getTool() {
        return tool;
    }

    @DataBoundSetter
    public void setTool(String tool) {
        this.tool = Util.fixEmpty(tool);
    }

    public String getJdk() {
        return jdk;
    }

    @DataBoundSetter
    public void setJdk(String jdk) {
        this.jdk = Util.fixEmpty(jdk);
    }

    public Object getInput() {
        return input;
    }

    @DataBoundSetter
    public void setInput(Object input) {
        this.input = input;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, this);
    }

    private static class Execution extends StepExecution {

        private final transient WithGroovyStep step;

        Execution(StepContext context, WithGroovyStep step) {
            super(context);
            this.step = step;
        }

        @Override
        public boolean start() throws Exception {
            FilePath base = WorkspaceList.tempDir(getContext().get(FilePath.class));
            base.mkdirs();
            FilePath tmp = base.createTempDir("jenkins-groovy-step", "");
            Map<String, String> env = new HashMap<>();
            // TODO these Remoting calls can block the CPS VM thread; need a SynchronousNonBlockingStepExecution analogue that applies only to the setup phase of a block-scoped step:
            if (step.tool != null) {
                GroovyInstallation installation = Groovy.DescriptorImpl.getGroovy(step.tool);
                if (installation == null) {
                    throw new AbortException("no such Groovy installation " + step.tool);
                }
                installation = installation.forNode(getContext().get(Node.class), getContext().get(TaskListener.class));
                installation = installation.forEnvironment(getContext().get(EnvVars.class));
                String home = installation.getHome();
                env.put("PATH+GROOVY", tmp.child(home).child("bin").getRemote());
            } else {
                FilePath bin = tmp.child("bin");
                FilePath groovySh = bin.child("groovy");
                groovySh.copyFrom(WithGroovyStep.class.getResource("groovy.sh"));
                groovySh.chmod(0755);
                bin.child("groovy.bat").copyFrom(WithGroovyStep.class.getResource("groovy.bat"));
                env.put("PATH+GROOVY", bin.getRemote());
                env.put("CLASSPATH+GROOVYALL", FindGroovyAllJAR.runIn(tmp.getChannel()));
            }
            if (step.jdk != null) {
                // avoid calling Jenkins.getJDK: https://github.com/jenkinsci/jenkins/pull/3147
                JDK jdk = null;
                for (JDK _jdk : Jenkins.getInstance().getDescriptorByType(JDK.DescriptorImpl.class).getInstallations()) {
                    if (_jdk.getName().equals(step.jdk)) {
                        jdk = _jdk;
                        break;
                    }
                }
                if (jdk == null) {
                    throw new AbortException("no such JDK installation " + step.jdk);
                }
                jdk = jdk.forNode(getContext().get(Node.class), getContext().get(TaskListener.class));
                jdk = jdk.forEnvironment(getContext().get(EnvVars.class));
                String home = jdk.getHome();
                env.put("PATH+JDK", tmp.child(home).child("bin").getRemote());
            }
            if (step.input != null) {
                try (OutputStream os = tmp.child("input.ser").write(); ObjectOutputStream oos = new ObjectOutputStream(os)) {
                    oos.writeObject(step.input);
                }
                tmp.child("Pipeline.groovy").copyFrom(WithGroovyStep.class.getResource("Pipeline.groovy"));
                env.put("CLASSPATH+GROOVY", tmp.getRemote());
            }
            getContext().newBodyInvoker().
                withContext(EnvironmentExpander.constant(env)).
                withCallback(new Callback(tmp)).
                start();
            return false;
        }

        /** Locates {@code groovy-all.jar} on a given node. */
        private static class FindGroovyAllJAR extends MasterToSlaveCallable<String, IOException> {

            static String runIn(VirtualChannel channel) throws IOException, InterruptedException {
                // Without the preloadJar call, we would not get back a JAR path, but just a directory containing only groovy/lang/Writable.class.
                if (channel instanceof Channel) {
                    ((Channel) channel).preloadJar(WithGroovyStep.class.getClassLoader(), /* arbitrary, but has no other deps to load from agent JVM */ Writable.class);
                }
                return channel.call(new FindGroovyAllJAR());
            }

            @Override
            public String call() throws IOException {
                return Which.jarFile(Writable.class).getAbsolutePath();
            }

        }

    }

    private static class Callback extends BodyExecutionCallback {

        private final String tmp;

        Callback(FilePath tmp) {
            this.tmp = tmp.getRemote();
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            try {
                FilePath ser = context.get(FilePath.class).child(tmp).child("output.ser");
                if (ser.exists()) {
                    try (InputStream is = ser.read(); ObjectInputStream ois = new ObjectInputStreamEx(is, GroovyObject.class.getClassLoader(), ClassFilter.DEFAULT)) {
                        context.onSuccess(ois.readObject());
                        return;
                    }
                }
            } catch (Exception x) {
                context.onFailure(x);
                return;
            }
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            context.onFailure(t);
        }

    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withGroovy";
        }

        @Override
        public String getDisplayName() {
            return "Execute Groovy script";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return ImmutableSet.of(FilePath.class, Node.class, EnvVars.class, TaskListener.class);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        public ListBoxModel doFillToolItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("(Default)", "");
            for (GroovyInstallation inst : Jenkins.getInstance().getDescriptorByType(GroovyInstallation.DescriptorImpl.class).getInstallations()) {
                m.add(inst.getName());
            }
            return m;
        }

        public ListBoxModel doFillJdkItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("(Default)", "");
            for (JDK inst : Jenkins.getInstance().getDescriptorByType(JDK.DescriptorImpl.class).getInstallations()) {
                m.add(inst.getName());
            }
            return m;
        }

    }

}
